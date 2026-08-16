/*
 * Copyright 2026 Spring AI Community
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

/**
 * Installation management for the Claude Code CLI: detect whether the CLI is present on
 * this machine, and install it from Anthropic's official release source when it is not.
 *
 * <p>
 * Installation runs Anthropic's own <em>native installer</em> script — the same one the
 * setup docs give as {@code curl -fsSL https://claude.ai/install.sh | bash} (or
 * {@code install.ps1} on Windows). The script accepts an optional target: {@code stable},
 * {@code latest} (the default) or a specific version such as {@code "2.1.233"} — the same
 * channel names as {@link ClaudeCliVersions.UpdateChannel}. On Unix it installs the
 * binary under {@code ~/.local/share/claude} with a {@code ~/.local/bin/claude} launcher,
 * a location {@link ClaudeCliDiscovery} already probes, so a freshly installed CLI is
 * found without any {@code PATH} change in the running JVM.
 * </p>
 *
 * <h2>Example</h2> <pre>{@code
 * if (!ClaudeCliInstaller.isInstalled()) {
 *     ClaudeCliInstaller.InstallResult result = ClaudeCliInstaller.install();
 *     System.out.println("Installed " + result.version() + " at " + result.path());
 * }
 * // or in one step:
 * String claudePath = ClaudeCliInstaller.ensureInstalled().path();
 * }</pre>
 *
 * <p>
 * The download location can be overridden with the system property
 * {@code claude.cli.installBaseUrl} (default {@code https://claude.ai}; the script names
 * {@code /install.sh} / {@code /install.ps1} are appended), e.g. for a corporate mirror
 * of the installer script.
 * </p>
 *
 * @see ClaudeCliDiscovery
 * @see ClaudeCliVersions
 */
public final class ClaudeCliInstaller {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeCliInstaller.class);

	private static final String DEFAULT_INSTALL_BASE_URL = "https://claude.ai";

	private static final String INSTALL_BASE_URL_PROPERTY = "claude.cli.installBaseUrl";

	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(1);

	/** The installer downloads a full CLI build, so allow a generous default. */
	private static final Duration DEFAULT_INSTALL_TIMEOUT = Duration.ofMinutes(10);

	private ClaudeCliInstaller() {
	}

	/** Lazily created so detection-only callers never construct an HTTP client. */
	private static final class Http {

		static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	}

	/**
	 * Outcome of an installation (or of {@link #ensureInstalled()} finding an existing
	 * CLI).
	 *
	 * <p>
	 * Like {@link ClaudeCliVersions.UpdateResult}, success is verified rather than
	 * assumed: after the installer script runs, the CLI is re-discovered and its version
	 * re-read; {@code path} and {@code version} therefore describe a CLI that is actually
	 * runnable on this machine.
	 * </p>
	 *
	 * @param alreadyInstalled {@code true} when no installation ran because a working CLI
	 * was already present
	 * @param path the discovered CLI executable path
	 * @param version the installed CLI version, e.g. {@code "2.1.233"}
	 * @param installerOutput combined stdout/stderr of the installer script (empty when
	 * {@code alreadyInstalled})
	 */
	public record InstallResult(boolean alreadyInstalled, String path, String version, String installerOutput) {

		/**
		 * Whether this result comes from an actual installation run (as opposed to a CLI
		 * that was already present).
		 * @return true if the installer script ran and produced this CLI
		 */
		public boolean freshlyInstalled() {
			return !alreadyInstalled;
		}

	}

	/**
	 * Checks whether a working Claude CLI is present on this machine, by probing the
	 * {@code PATH} and the common install locations (see {@link ClaudeCliDiscovery}).
	 * Never throws.
	 *
	 * <p>
	 * Discovery results are cached for the JVM's lifetime; if the CLI was
	 * installed/removed outside this class after the first check, call
	 * {@link ClaudeCliDiscovery#forceRediscovery()} to observe the change
	 * ({@link #install} variants do this automatically).
	 * </p>
	 * @return true if a Claude CLI executable was found and responds to {@code --version}
	 */
	public static boolean isInstalled() {
		return ClaudeCliDiscovery.isClaudeCliAvailable();
	}

	/**
	 * The discovered Claude CLI executable path, when one is installed.
	 * @return the executable path, or empty if no CLI was found
	 */
	public static Optional<String> installedPath() {
		if (!ClaudeCliDiscovery.isClaudeCliAvailable()) {
			return Optional.empty();
		}
		return Optional.ofNullable(ClaudeCliDiscovery.getDiscoveredPath());
	}

	/**
	 * Ensures a Claude CLI is available: returns the existing installation when one is
	 * found, otherwise installs the newest build on the
	 * {@link ClaudeCliVersions.UpdateChannel#LATEST latest} channel.
	 * @return the existing or freshly installed CLI (see
	 * {@link InstallResult#alreadyInstalled()})
	 * @throws ClaudeSDKException if no CLI is present and installation fails
	 */
	public static InstallResult ensureInstalled() {
		return ensureInstalled(ClaudeCliVersions.UpdateChannel.LATEST);
	}

	/**
	 * Ensures a Claude CLI is available: returns the existing installation when one is
	 * found (whatever its channel/version), otherwise installs the newest build on the
	 * given channel.
	 * @param channel the release channel to install from when nothing is installed
	 * @return the existing or freshly installed CLI (see
	 * {@link InstallResult#alreadyInstalled()})
	 * @throws ClaudeSDKException if no CLI is present and installation fails
	 */
	public static InstallResult ensureInstalled(ClaudeCliVersions.UpdateChannel channel) {
		if (ClaudeCliDiscovery.isClaudeCliAvailable()) {
			String path = ClaudeCliDiscovery.getDiscoveredPath();
			return new InstallResult(true, path, ClaudeCliVersions.getInstalledVersion(path), "");
		}
		return install(channel);
	}

	/**
	 * Installs the newest Claude CLI build on the
	 * {@link ClaudeCliVersions.UpdateChannel#LATEST latest} channel, with the default
	 * 10-minute timeout.
	 * @return the verified installation
	 * @throws ClaudeSDKException if the installer cannot be downloaded or run, or no
	 * working CLI is discoverable afterwards
	 */
	public static InstallResult install() {
		return install(ClaudeCliVersions.UpdateChannel.LATEST);
	}

	/**
	 * Installs the newest Claude CLI build on the given release channel, with the default
	 * 10-minute timeout.
	 * @param channel the release channel to install from
	 * @return the verified installation
	 * @throws ClaudeSDKException if the installer cannot be downloaded or run, or no
	 * working CLI is discoverable afterwards
	 */
	public static InstallResult install(ClaudeCliVersions.UpdateChannel channel) {
		return install(channel.tag(), DEFAULT_INSTALL_TIMEOUT);
	}

	/**
	 * Installs the given installer target with the default 10-minute timeout.
	 * @param target an installer target: {@code "stable"}, {@code "latest"}, or a
	 * specific version such as {@code "2.1.233"}
	 * @return the verified installation
	 * @throws ClaudeSDKException if the installer cannot be downloaded or run, or no
	 * working CLI is discoverable afterwards
	 */
	public static InstallResult install(String target) {
		return install(target, DEFAULT_INSTALL_TIMEOUT);
	}

	/**
	 * Installs the given installer target by downloading and running Anthropic's official
	 * install script ({@code install.sh} via {@code bash} on Unix, {@code install.ps1}
	 * via {@code powershell} on Windows).
	 *
	 * <p>
	 * After the script finishes, CLI discovery is re-run
	 * ({@link ClaudeCliDiscovery#forceRediscovery()}) and the installed version read back
	 * — the returned result always describes a CLI that actually runs. The script's own
	 * output is carried in {@link InstallResult#installerOutput()} for logging/diagnosis.
	 * </p>
	 * @param target an installer target: {@code "stable"}, {@code "latest"}, or a
	 * specific version such as {@code "2.1.233"}
	 * @param timeout maximum time to allow the installer script to run
	 * @return the verified installation
	 * @throws ClaudeSDKException if the installer cannot be downloaded or run, or no
	 * working CLI is discoverable afterwards
	 */
	public static InstallResult install(String target, Duration timeout) {
		if (target == null || target.isBlank()) {
			throw new IllegalArgumentException("target must be a channel name or version (e.g. \"latest\")");
		}
		Path script = downloadInstallerScript();
		ProcessResult result;
		try {
			List<String> command = installerCommand(script, target.trim());
			logger.info("Running Claude CLI installer: {}", command);
			result = new ProcessExecutor().command(command)
				.timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
				.readOutput(true)
				.execute();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ClaudeSDKException("Interrupted while running the Claude CLI installer", e);
		}
		catch (Exception e) {
			throw new ClaudeSDKException("Failed to run the Claude CLI installer: " + e.getMessage(), e);
		}
		finally {
			deleteQuietly(script);
		}

		String output = result.outputUTF8();
		// Trust the outcome, not the exit code: verify a working CLI is now discoverable.
		ClaudeCliDiscovery.forceRediscovery();
		String path;
		try {
			path = ClaudeCliDiscovery.discoverClaudePath();
		}
		catch (ClaudeCliDiscovery.ClaudeCliNotFoundException e) {
			throw new ClaudeSDKException(
					"Claude CLI installer finished (exit " + result.getExitValue()
							+ ") but no working CLI was found afterwards. Installer output: " + truncate(output, 1000),
					e);
		}
		String version = ClaudeCliVersions.getInstalledVersion(path);
		logger.info("Claude CLI {} installed at {}", version, path);
		return new InstallResult(false, path, version, output);
	}

	// ------------------------------------------------------------------
	// Internals (package-visible for tests)
	// ------------------------------------------------------------------

	/**
	 * The platform's installer command: {@code bash <script> <target>} on Unix,
	 * {@code powershell -NoProfile -ExecutionPolicy Bypass -File <script> <target>} on
	 * Windows. The target is passed as a separate argument (no shell interpolation).
	 */
	static List<String> installerCommand(Path script, String target) {
		List<String> command = new ArrayList<>();
		if (isWindows()) {
			command.add("powershell");
			command.add("-NoProfile");
			command.add("-ExecutionPolicy");
			command.add("Bypass");
			command.add("-File");
		}
		else {
			command.add("bash");
		}
		command.add(script.toString());
		command.add(target);
		return command;
	}

	/** The full installer script URL for this platform. */
	static String installerScriptUrl() {
		String base = System.getProperty(INSTALL_BASE_URL_PROPERTY, DEFAULT_INSTALL_BASE_URL);
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + (isWindows() ? "/install.ps1" : "/install.sh");
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static Path downloadInstallerScript() {
		String url = installerScriptUrl();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(DOWNLOAD_TIMEOUT)
			.header("Accept", "*/*")
			.GET()
			.build();
		try {
			HttpResponse<String> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new ClaudeSDKException("GET " + url + " returned " + response.statusCode() + " body: "
						+ truncate(response.body(), 300));
			}
			Path script = Files.createTempFile("claude-install-", isWindows() ? ".ps1" : ".sh");
			Files.writeString(script, response.body(), StandardCharsets.UTF_8);
			return script;
		}
		catch (ClaudeSDKException e) {
			throw e;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ClaudeSDKException("Interrupted while downloading the Claude CLI installer from " + url, e);
		}
		catch (IOException e) {
			throw new ClaudeSDKException(
					"Failed to download the Claude CLI installer from " + url + ": " + e.getMessage(), e);
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException e) {
			logger.debug("Could not delete installer temp file {}", file, e);
		}
	}

	private static String truncate(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		return (trimmed.length() <= maxLength) ? trimmed : trimmed.substring(0, maxLength) + "...";
	}

}
