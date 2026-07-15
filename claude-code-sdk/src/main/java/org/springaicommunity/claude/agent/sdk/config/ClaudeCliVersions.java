/*
 * Copyright 2025 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.claude.agent.sdk.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Version management for the Claude Code CLI: read the installed version, discover the
 * latest available version, and trigger an update.
 *
 * <p>
 * The <em>installed</em> version comes from running {@code claude --version}. The
 * <em>latest available</em> version comes from the npm registry's
 * <a href="https://registry.npmjs.org/-/package/@anthropic-ai/claude-code/dist-tags">
 * dist-tags</a> for the {@code @anthropic-ai/claude-code} package — the same
 * {@code stable} / {@code latest} channel names that {@code claude install [target]}
 * accepts and that the CLI's {@code autoUpdatesChannel} setting selects. (Native
 * installations check {@code downloads.claude.ai/claude-code-releases/<channel>}, which
 * mirrors these tags; the npm registry is used here because it is publicly reachable and
 * serves all channels in one response.)
 * </p>
 *
 * <p>
 * The CLI itself has no check-only mode — {@code claude update} always installs when an
 * update is found. {@link #checkForUpdate()} fills that gap: it surfaces both versions so
 * the caller can decide, and {@link #update()} performs the actual update.
 * </p>
 *
 * <h2>Example</h2> <pre>{@code
 * ClaudeCliVersions.VersionCheck check = ClaudeCliVersions.checkForUpdate();
 * System.out.println("Installed: " + check.installedVersion());
 * System.out.println("Latest:    " + check.latestVersion());
 * if (check.isUpdateAvailable()) {
 *     ClaudeCliVersions.UpdateResult result = ClaudeCliVersions.update();
 *     System.out.println(result.wasUpdated() ? "Updated to " + result.currentVersion()
 *             : "Update did not complete: " + result.output());
 * }
 * }</pre>
 *
 * <p>
 * The registry base URL can be overridden with the system property
 * {@code claude.cli.registryUrl} (default {@code https://registry.npmjs.org}), e.g. for
 * corporate npm mirrors. The CLI executable is located via {@link ClaudeCliDiscovery}
 * (honouring the {@code claude.cli.path} system property) unless an explicit path is
 * passed.
 * </p>
 */
public final class ClaudeCliVersions {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeCliVersions.class);

	private static final String DEFAULT_REGISTRY_URL = "https://registry.npmjs.org";

	private static final String DIST_TAGS_PATH = "/-/package/@anthropic-ai/claude-code/dist-tags";

	private static final String REGISTRY_URL_PROPERTY = "claude.cli.registryUrl";

	private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(15);

	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

	/** Updates download and install a new CLI build, so allow a generous default. */
	private static final Duration DEFAULT_UPDATE_TIMEOUT = Duration.ofMinutes(5);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ClaudeCliVersions() {
	}

	/**
	 * Lazily creates the HTTP client so callers that only touch the local CLI (e.g.
	 * {@link #getInstalledVersion()}) never construct one.
	 */
	private static final class Http {

		static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(HTTP_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	}

	/**
	 * Release channels of the Claude Code CLI, matching both the npm dist-tags of
	 * {@code @anthropic-ai/claude-code} and the targets accepted by
	 * {@code claude install [target]} / the {@code autoUpdatesChannel} setting.
	 */
	public enum UpdateChannel {

		/** Delayed release channel (roughly a week behind {@link #LATEST}). */
		STABLE("stable"),

		/** Current release channel — the CLI's default auto-update channel. */
		LATEST("latest"),

		/** Pre-release channel. */
		NEXT("next");

		private final String tag;

		UpdateChannel(String tag) {
			this.tag = tag;
		}

		/**
		 * The npm dist-tag / CLI install target name for this channel.
		 * @return the lowercase tag name, e.g. {@code "latest"}
		 */
		public String tag() {
			return this.tag;
		}

	}

	/**
	 * Result of comparing the installed CLI version against a release channel.
	 *
	 * @param installedVersion the locally installed version, e.g. {@code "2.1.205"}
	 * @param latestVersion the newest version published on the channel
	 * @param channel the release channel that was checked
	 */
	public record VersionCheck(String installedVersion, String latestVersion, UpdateChannel channel) {

		/**
		 * Whether the channel has a newer version than the one installed.
		 * @return true if updating would install a newer version
		 */
		public boolean isUpdateAvailable() {
			return compareVersions(latestVersion, installedVersion) > 0;
		}

	}

	/**
	 * Outcome of running {@code claude update}.
	 *
	 * <p>
	 * The CLI has been observed to exit with code 0 even when the update fails (the
	 * failure is only reported in the output), so {@link #wasUpdated()} — a before/after
	 * comparison of the installed version — is the reliable success signal.
	 * {@link #output()} carries the CLI's own diagnostics for logging or display.
	 * </p>
	 *
	 * @param previousVersion the installed version before the update ran
	 * @param currentVersion the installed version after the update ran
	 * @param exitCode the exit code of {@code claude update} (0 does not imply success)
	 * @param output combined stdout/stderr of the update command
	 */
	public record UpdateResult(String previousVersion, String currentVersion, int exitCode, String output) {

		/**
		 * Whether the installed version actually changed.
		 * @return true if the CLI reports a different version than before the update
		 */
		public boolean wasUpdated() {
			return currentVersion != null && !currentVersion.equals(previousVersion);
		}

	}

	/**
	 * Gets the version of the locally installed Claude CLI by running
	 * {@code claude --version}. The executable is located via
	 * {@link ClaudeCliDiscovery#discoverClaudePath()}.
	 * @return the installed version, e.g. {@code "2.1.205"}
	 * @throws ClaudeSDKException if the CLI cannot be found or its version cannot be read
	 */
	public static String getInstalledVersion() {
		return getInstalledVersion(discoverPath());
	}

	/**
	 * Gets the version of the Claude CLI at the given path by running
	 * {@code <claudePath> --version}.
	 * @param claudePath path to the Claude CLI executable
	 * @return the installed version, e.g. {@code "2.1.205"}
	 * @throws ClaudeSDKException if the executable fails to run or reports no version
	 */
	public static String getInstalledVersion(String claudePath) {
		try {
			ProcessResult result = new ProcessExecutor().command(claudePath, "--version")
				.timeout(VERSION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
				.readOutput(true)
				.execute();
			if (result.getExitValue() != 0) {
				throw new ClaudeSDKException("'" + claudePath + " --version' exited with code "
						+ result.getExitValue() + ": " + truncate(result.outputUTF8(), 300));
			}
			return parseVersionOutput(result.outputUTF8());
		}
		catch (ClaudeSDKException e) {
			throw e;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ClaudeSDKException("Interrupted while reading Claude CLI version", e);
		}
		catch (Exception e) {
			throw new ClaudeSDKException("Failed to run '" + claudePath + " --version': " + e.getMessage(), e);
		}
	}

	/**
	 * Gets the newest Claude CLI version published on the {@link UpdateChannel#LATEST
	 * latest} channel.
	 * @return the latest available version, e.g. {@code "2.1.205"}
	 * @throws ClaudeSDKException if the registry cannot be reached or returns an
	 * unexpected response
	 */
	public static String getLatestAvailableVersion() {
		return getLatestAvailableVersion(UpdateChannel.LATEST);
	}

	/**
	 * Gets the newest Claude CLI version published on the given release channel, from the
	 * npm registry dist-tags of {@code @anthropic-ai/claude-code}.
	 * @param channel the release channel to query
	 * @return the newest version on that channel, e.g. {@code "2.1.197"} for
	 * {@link UpdateChannel#STABLE}
	 * @throws ClaudeSDKException if the registry cannot be reached or returns an
	 * unexpected response
	 */
	public static String getLatestAvailableVersion(UpdateChannel channel) {
		String url = registryBaseUrl() + DIST_TAGS_PATH;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(HTTP_TIMEOUT)
			.header("Accept", "application/json")
			.GET()
			.build();
		try {
			HttpResponse<String> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new ClaudeSDKException("GET " + url + " returned " + response.statusCode() + " body: "
						+ truncate(response.body(), 300));
			}
			String version = parseDistTags(response.body(), channel);
			logger.debug("Latest Claude CLI version on channel '{}': {}", channel.tag(), version);
			return version;
		}
		catch (ClaudeSDKException e) {
			throw e;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ClaudeSDKException("Interrupted while querying " + url, e);
		}
		catch (Exception e) {
			throw new ClaudeSDKException("Failed to query latest Claude CLI version from " + url + ": "
					+ e.getMessage(), e);
		}
	}

	/**
	 * Checks whether a newer Claude CLI version is available on the
	 * {@link UpdateChannel#LATEST latest} channel, without installing anything.
	 * @return the installed and latest versions plus the comparison result
	 * @throws ClaudeSDKException if the installed version or the registry cannot be read
	 */
	public static VersionCheck checkForUpdate() {
		return checkForUpdate(UpdateChannel.LATEST);
	}

	/**
	 * Checks whether a newer Claude CLI version is available on the given channel,
	 * without installing anything.
	 * @param channel the release channel to compare against
	 * @return the installed and latest versions plus the comparison result
	 * @throws ClaudeSDKException if the installed version or the registry cannot be read
	 */
	public static VersionCheck checkForUpdate(UpdateChannel channel) {
		String installed = getInstalledVersion();
		String latest = getLatestAvailableVersion(channel);
		return new VersionCheck(installed, latest, channel);
	}

	/**
	 * Updates the Claude CLI by running {@code claude update} (which checks for a newer
	 * version on the CLI's configured channel and installs it if found), with a default
	 * timeout of 5 minutes.
	 * @return the update outcome; inspect {@link UpdateResult#wasUpdated()} rather than
	 * the exit code
	 * @throws ClaudeSDKException if the CLI cannot be found or the update command fails
	 * to run
	 */
	public static UpdateResult update() {
		return update(DEFAULT_UPDATE_TIMEOUT);
	}

	/**
	 * Updates the Claude CLI by running {@code claude update} with the given timeout.
	 * @param timeout maximum time to allow the update command to run
	 * @return the update outcome; inspect {@link UpdateResult#wasUpdated()} rather than
	 * the exit code
	 * @throws ClaudeSDKException if the CLI cannot be found or the update command fails
	 * to run
	 */
	public static UpdateResult update(Duration timeout) {
		return update(discoverPath(), timeout);
	}

	/**
	 * Updates the Claude CLI at the given path by running {@code <claudePath> update}.
	 * @param claudePath path to the Claude CLI executable
	 * @param timeout maximum time to allow the update command to run
	 * @return the update outcome; inspect {@link UpdateResult#wasUpdated()} rather than
	 * the exit code
	 * @throws ClaudeSDKException if the update command fails to run or the version cannot
	 * be re-read afterwards
	 */
	public static UpdateResult update(String claudePath, Duration timeout) {
		String before = getInstalledVersion(claudePath);
		ProcessResult result;
		try {
			result = new ProcessExecutor().command(claudePath, "update")
				.timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
				.readOutput(true)
				.execute();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ClaudeSDKException("Interrupted while running '" + claudePath + " update'", e);
		}
		catch (Exception e) {
			throw new ClaudeSDKException("Failed to run '" + claudePath + " update': " + e.getMessage(), e);
		}
		String after = getInstalledVersion(claudePath);
		UpdateResult updateResult = new UpdateResult(before, after, result.getExitValue(), result.outputUTF8());
		if (updateResult.wasUpdated()) {
			logger.info("Claude CLI updated from {} to {}", before, after);
		}
		else {
			logger.info("Claude CLI update left version at {} (exit code {})", after, result.getExitValue());
		}
		return updateResult;
	}

	/**
	 * Compares two version strings segment by segment ({@code "2.1.10"} &gt;
	 * {@code "2.1.9"}). Numeric segments compare numerically, missing segments count as 0
	 * ({@code "2.1"} equals {@code "2.1.0"}), a leading {@code v} is ignored, and a
	 * pre-release suffix ({@code "2.1.0-beta"}) sorts before its release version.
	 * @param v1 first version string
	 * @param v2 second version string
	 * @return a negative number, zero, or a positive number as {@code v1} is older than,
	 * equal to, or newer than {@code v2}
	 * @throws IllegalArgumentException if either version is null or blank
	 */
	public static int compareVersions(String v1, String v2) {
		String[] first = splitVersion(v1);
		String[] second = splitVersion(v2);
		int release = compareRelease(first[0], second[0]);
		if (release != 0) {
			return release;
		}
		return comparePreRelease(first[1], second[1]);
	}

	// ------------------------------------------------------------------
	// Parsing helpers (package-visible for tests)
	// ------------------------------------------------------------------

	/**
	 * Parses the output of {@code claude --version} (e.g.
	 * {@code "2.1.205 (Claude Code)"}) down to the bare version string.
	 */
	static String parseVersionOutput(String rawOutput) {
		String trimmed = (rawOutput != null) ? rawOutput.trim() : "";
		if (!trimmed.isEmpty()) {
			String version = trimmed.split("\\s+")[0];
			if (version.startsWith("v") || version.startsWith("V")) {
				version = version.substring(1);
			}
			if (!version.isEmpty() && Character.isDigit(version.charAt(0))) {
				return version;
			}
		}
		throw new ClaudeSDKException(
				"Could not parse a version from 'claude --version' output: " + truncate(trimmed, 120));
	}

	/**
	 * Extracts one channel's version from a npm dist-tags JSON document such as
	 * {@code {"stable":"2.1.197","next":"2.1.206","latest":"2.1.205"}}.
	 */
	static String parseDistTags(String json, UpdateChannel channel) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		}
		catch (Exception e) {
			throw new ClaudeSDKException("Malformed dist-tags response: " + truncate(json, 300), e);
		}
		String version = root.path(channel.tag()).asText("");
		if (version.isBlank()) {
			throw new ClaudeSDKException("Channel '" + channel.tag() + "' not present in dist-tags response: "
					+ truncate(json, 300));
		}
		return version;
	}

	// ------------------------------------------------------------------
	// Internals
	// ------------------------------------------------------------------

	private static String discoverPath() {
		try {
			return ClaudeCliDiscovery.discoverClaudePath();
		}
		catch (ClaudeCliDiscovery.ClaudeCliNotFoundException e) {
			throw new ClaudeSDKException(e.getMessage(), e);
		}
	}

	private static String registryBaseUrl() {
		String base = System.getProperty(REGISTRY_URL_PROPERTY, DEFAULT_REGISTRY_URL);
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	/** Splits a version into [release, preRelease], stripping a leading 'v'. */
	private static String[] splitVersion(String version) {
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("Version must not be null or blank");
		}
		String cleaned = version.trim();
		if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
			cleaned = cleaned.substring(1);
		}
		// Ignore build metadata (semver: everything after '+')
		int plus = cleaned.indexOf('+');
		if (plus >= 0) {
			cleaned = cleaned.substring(0, plus);
		}
		int dash = cleaned.indexOf('-');
		if (dash >= 0) {
			return new String[] { cleaned.substring(0, dash), cleaned.substring(dash + 1) };
		}
		return new String[] { cleaned, "" };
	}

	private static int compareRelease(String r1, String r2) {
		String[] parts1 = r1.split("\\.");
		String[] parts2 = r2.split("\\.");
		int length = Math.max(parts1.length, parts2.length);
		for (int i = 0; i < length; i++) {
			String s1 = (i < parts1.length) ? parts1[i] : "0";
			String s2 = (i < parts2.length) ? parts2[i] : "0";
			int result = compareSegment(s1, s2);
			if (result != 0) {
				return result;
			}
		}
		return 0;
	}

	private static int compareSegment(String s1, String s2) {
		boolean numeric1 = isNumeric(s1);
		boolean numeric2 = isNumeric(s2);
		if (numeric1 && numeric2) {
			return Long.compare(Long.parseLong(s1), Long.parseLong(s2));
		}
		if (numeric1 != numeric2) {
			// Semver: numeric identifiers sort before non-numeric ones
			return numeric1 ? -1 : 1;
		}
		return s1.compareTo(s2);
	}

	private static int comparePreRelease(String p1, String p2) {
		if (p1.isEmpty() && p2.isEmpty()) {
			return 0;
		}
		// A release version outranks any of its pre-releases
		if (p1.isEmpty()) {
			return 1;
		}
		if (p2.isEmpty()) {
			return -1;
		}
		String[] parts1 = p1.split("\\.");
		String[] parts2 = p2.split("\\.");
		int length = Math.min(parts1.length, parts2.length);
		for (int i = 0; i < length; i++) {
			int result = compareSegment(parts1[i], parts2[i]);
			if (result != 0) {
				return result;
			}
		}
		return Integer.compare(parts1.length, parts2.length);
	}

	private static boolean isNumeric(String value) {
		if (value.isEmpty() || value.length() > 18) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static String truncate(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		return (trimmed.length() <= maxLength) ? trimmed : trimmed.substring(0, maxLength) + "...";
	}

}
