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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the CLI's authentication status — including the identity of the signed-in
 * Anthropic account — via {@code claude auth status --json}. This is the supported way to
 * answer "which account is this machine's Claude CLI signed in as": the same account
 * every SDK-spawned session (including the
 * {@code org.springaicommunity.claude.agent.sdk.usage.ClaudeAccountRateLimits} probe)
 * authenticates as, unless overridden per call with an explicit OAuth token or
 * environment.
 *
 * <p>
 * What the CLI reports depends on how it is authenticated (fields observed on CLI
 * 2.1.251):
 * </p>
 * <ul>
 * <li><b>Interactive claude.ai login</b> ({@code authMethod: "claude.ai"}): full identity
 * — {@code email}, {@code orgId}, {@code orgName}, {@code subscriptionType} (e.g.
 * {@code "max"}).</li>
 * <li><b>Injected token auth</b> (e.g. {@code CLAUDE_CODE_OAUTH_TOKEN},
 * {@code authMethod: "oauth_token"}): auth state only — the identity fields are
 * null.</li>
 * <li><b>Not logged in</b>: {@code loggedIn} is false.</li>
 * </ul>
 *
 * <h2>Example</h2> <pre>{@code
 * ClaudeAuth.AuthStatus auth = ClaudeAuth.status();
 * if (auth.loggedIn() && auth.email() != null) {
 *     System.out.printf("Signed in as %s (%s plan)%n", auth.email(), auth.subscriptionType());
 * }
 * }</pre>
 *
 * <p>
 * The command is local and instant — no session is started and no tokens are consumed.
 * Because the wire format is produced by the CLI and evolves with it, {@link AuthStatus}
 * pairs typed accessors for every field observed on the wire with
 * {@link AuthStatus#allValues()}, a flattened {@code path -> string} map of the raw JSON
 * in which unknown/new fields are preserved.
 * </p>
 *
 * <p>
 * When {@code status()} cannot name the account (token auth), {@link #profile(String)}
 * resolves an OAuth token to its account identity directly — at the cost of calling an
 * undocumented endpoint; see its documentation for the trade-offs.
 * </p>
 */
public final class ClaudeAuth {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Duration CLI_TIMEOUT = Duration.ofSeconds(60);

	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

	private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

	private static final String PROFILE_PATH = "/api/oauth/profile";

	/** OAuth scope the profile endpoint requires (it accepts {@code user:office} too). */
	public static final String USER_PROFILE_SCOPE = "user:profile";

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private ClaudeAuth() {
	}

	/**
	 * The CLI's authentication status, as printed by {@code claude auth status --json}.
	 *
	 * <p>
	 * Typed accessors cover every field observed on the wire as of Claude CLI 2.1.251;
	 * any field not listed here (including future additions such as
	 * {@code projectsDirectory} or {@code analyticsDisabled}) is still reachable through
	 * {@link #allValues()}.
	 * </p>
	 *
	 * @param loggedIn whether the CLI has working credentials
	 * @param authMethod how the CLI is authenticated: {@code "claude.ai"} for an
	 * interactive subscription login, {@code "oauth_token"} for an injected long-lived
	 * token, {@code "api_key"} for API-key billing; null when the CLI omits it
	 * @param apiProvider active API backend, e.g. {@code "firstParty"},
	 * {@code "bedrock"}, {@code "vertex"}; null when omitted
	 * @param email the signed-in account's email address, or null when the auth method
	 * carries no identity (token/API-key auth) or the CLI is logged out
	 * @param orgId the account's organization UUID, or null when not reported
	 * @param orgName the organization's display name, or null when not reported
	 * @param subscriptionType the claude.ai plan, e.g. {@code "pro"} or {@code "max"};
	 * null when not reported (API-key billing has no subscription)
	 * @param allValues flattened raw JSON of the CLI's output, all values as strings
	 * (nested objects as {@code "a.b"} paths); never null
	 */
	public record AuthStatus(boolean loggedIn, String authMethod, String apiProvider, String email, String orgId,
			String orgName, String subscriptionType, Map<String, String> allValues) {

		/**
		 * Whether the CLI reported who is signed in — true only when an {@link #email()}
		 * is present. False for injected-token and API-key auth, where the CLI knows it
		 * has working credentials but not the account behind them.
		 */
		public boolean hasIdentity() {
			return email != null;
		}

	}

	/**
	 * Reads the CLI's current authentication status.
	 * @return the parsed status; when the CLI is logged out this still returns normally
	 * with {@link AuthStatus#loggedIn()} false
	 * @throws IOException if the CLI cannot be located, times out, or prints unparsable
	 * output
	 */
	public static AuthStatus status() throws IOException {
		List<String> command = List.of(claudeBinary(), "auth", "status", "--json");
		CommandResult r = run(command, CLI_TIMEOUT);
		// A logged-out CLI may exit non-zero while still printing valid JSON status —
		// prefer the JSON whenever it parses
		try {
			return parseStatus(r.stdout());
		}
		catch (IOException parseFailure) {
			if (r.exitCode() != 0) {
				throw new IOException(
						"`" + String.join(" ", command) + "` failed (exit " + r.exitCode() + "): " + errorDetail(r));
			}
			throw parseFailure;
		}
	}

	/**
	 * Parses the JSON object printed by {@code claude auth status --json}. Exposed so
	 * callers can parse captured output (fixtures, logs) without touching the CLI.
	 * @param json the raw JSON object
	 * @return the parsed status
	 * @throws IOException if the input is not a JSON object
	 */
	public static AuthStatus parseStatus(String json) throws IOException {
		JsonNode root = json == null || json.isBlank() ? null : MAPPER.readTree(json);
		if (root == null || !root.isObject()) {
			throw new IOException(
					"Expected a JSON object from `claude auth status --json` but got: " + truncate(json, 300));
		}
		return new AuthStatus(root.path("loggedIn").asBoolean(false), text(root, "authMethod"),
				text(root, "apiProvider"), text(root, "email"), text(root, "orgId"), text(root, "orgName"),
				text(root, "subscriptionType"), flatten(root));
	}

	/**
	 * The account behind an OAuth token, as returned by the {@code /api/oauth/profile}
	 * endpoint.
	 *
	 * @param accountUuid the account's UUID
	 * @param email the account's email address
	 * @param fullName the account holder's full name, or null when not reported
	 * @param displayName the account holder's display name, or null when not reported
	 * @param organizationUuid the account's organization UUID, or null when not reported
	 * @param organizationName the organization's display name, or null when not reported
	 * @param allValues flattened raw JSON of the endpoint's response, all values as
	 * strings (nested objects as {@code "a.b"} paths — e.g.
	 * {@code "organization.rate_limit_tier"}); never null
	 */
	public record OAuthProfile(String accountUuid, String email, String fullName, String displayName,
			String organizationUuid, String organizationName, Map<String, String> allValues) {
	}

	/**
	 * Resolves an OAuth token to the account it belongs to — the answer to "whose token
	 * is this" that {@link #status()} cannot give under token auth.
	 *
	 * <p>
	 * <b>Undocumented API.</b> This calls {@code GET /api/oauth/profile} with the token
	 * as a Bearer credential — the same endpoint the Claude CLI itself uses internally to
	 * resolve identity during login, but not a published Anthropic API: its shape and
	 * availability can change without notice. Prefer {@link #status()} wherever the CLI
	 * is interactively logged in.
	 * </p>
	 *
	 * <p>
	 * <b>Which tokens work:</b> the endpoint requires the {@code user:profile} (or
	 * {@code user:office}) OAuth scope. The interactive login's access token carries it
	 * (readable from this machine via {@code ClaudeCloudSessions.getClaudeOAuthToken()});
	 * long-lived {@code claude setup-token} tokens do <em>not</em>, and are rejected with
	 * {@code oauth_scope_insufficient} — this method turns that into a descriptive
	 * {@link IOException}. Check
	 * {@code ClaudeCloudSessions.getClaudeOAuthCredentials().scopes()} to know in
	 * advance.
	 * </p>
	 *
	 * <pre>{@code
	 * // The machine's interactive login token has the required scope:
	 * ClaudeAuth.OAuthProfile profile = ClaudeAuth.profile(ClaudeCloudSessions.getClaudeOAuthToken());
	 * System.out.printf("Token belongs to %s (%s)%n", profile.email(), profile.organizationName());
	 * }</pre>
	 * @param oauthToken the token to resolve; must be non-blank
	 * @return the account the token belongs to
	 * @throws IOException if the request fails, the token lacks the required scope, is
	 * expired/invalid, or the response cannot be parsed
	 * @throws InterruptedException if interrupted while waiting for the response
	 */
	public static OAuthProfile profile(String oauthToken) throws IOException, InterruptedException {
		if (oauthToken == null || oauthToken.isBlank()) {
			throw new IllegalArgumentException("oauthToken must be non-blank");
		}
		String url = baseUrl() + PROFILE_PATH;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(HTTP_TIMEOUT)
			.header("Authorization", "Bearer " + oauthToken)
			.header("Content-Type", "application/json")
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 200) {
			return parseProfile(response.body());
		}
		throw profileError(url, response.statusCode(), response.body());
	}

	/**
	 * Parses the JSON object returned by {@code GET /api/oauth/profile}. Exposed so
	 * callers can parse captured output (fixtures, logs) without touching the endpoint.
	 * @param json the raw JSON object
	 * @return the parsed profile
	 * @throws IOException if the input is not a JSON object
	 */
	public static OAuthProfile parseProfile(String json) throws IOException {
		JsonNode root = json == null || json.isBlank() ? null : MAPPER.readTree(json);
		if (root == null || !root.isObject()) {
			throw new IOException("Expected a JSON object from " + PROFILE_PATH + " but got: " + truncate(json, 300));
		}
		JsonNode account = root.path("account");
		JsonNode organization = root.path("organization");
		return new OAuthProfile(text(account, "uuid"), text(account, "email_address"), text(account, "full_name"),
				text(account, "display_name"), text(organization, "uuid"), text(organization, "name"), flatten(root));
	}

	/**
	 * Builds the exception for a non-200 profile response, recognizing the scope
	 * rejection that long-lived {@code claude setup-token} tokens produce.
	 * Package-visible for tests.
	 */
	static IOException profileError(String url, int statusCode, String body) {
		if (body != null && body.contains("oauth_scope_insufficient")) {
			return new IOException("GET " + url + " returned " + statusCode + ": the token lacks the "
					+ USER_PROFILE_SCOPE + " OAuth scope. Long-lived `claude setup-token` tokens don't carry it — "
					+ "use the interactive login's access token (ClaudeCloudSessions.getClaudeOAuthToken()). "
					+ "Body: " + truncate(body, 300));
		}
		String hint = statusCode == 401 ? " (expired or invalid token?)" : "";
		return new IOException("GET " + url + " returned " + statusCode + hint + " body: " + truncate(body, 300));
	}

	private static String baseUrl() {
		String base = System.getProperty("claude.auth.baseUrl", DEFAULT_BASE_URL);
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	// ------------------------------------------------------------------
	// Parsing helpers
	// ------------------------------------------------------------------

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}

	private static Map<String, String> flatten(JsonNode node) {
		Map<String, String> out = new LinkedHashMap<>();
		flattenInto("", node, out);
		return Collections.unmodifiableMap(out);
	}

	private static void flattenInto(String prefix, JsonNode node, Map<String, String> out) {
		if (node.isObject()) {
			node.properties()
				.forEach(
						e -> flattenInto(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out));
		}
		else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				flattenInto(prefix + "." + i, node.get(i), out);
			}
		}
		else {
			out.put(prefix, node.isNull() ? "null" : node.asText());
		}
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return "null";
		}
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}

	// ------------------------------------------------------------------
	// CLI invocation
	// ------------------------------------------------------------------

	private static String claudeBinary() throws IOException {
		try {
			return ClaudeCliDiscovery.discoverClaudePath();
		}
		catch (ClaudeCliDiscovery.ClaudeCliNotFoundException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	private record CommandResult(int exitCode, String stdout, String stderr) {
	}

	private static String errorDetail(CommandResult r) {
		String err = r.stderr().strip();
		if (!err.isEmpty()) {
			return truncate(err, 300);
		}
		return truncate(r.stdout().strip(), 300);
	}

	/**
	 * Runs a CLI command keeping stdout and stderr separate — stdout must stay pure JSON
	 * for parsing, stderr is kept for diagnostics.
	 */
	private static CommandResult run(List<String> command, Duration timeout) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(command);
		Process p = pb.start();
		StringBuilder err = new StringBuilder();
		Thread errThread = new Thread(() -> drain(p.getErrorStream(), err), "claude-auth-stderr");
		errThread.setDaemon(true);
		errThread.start();
		String out;
		try (InputStream in = p.getInputStream()) {
			out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		try {
			if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				p.destroyForcibly();
				throw new IOException("`" + String.join(" ", command) + "` timed out after " + timeout);
			}
			errThread.join(1000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
			throw new IOException("Interrupted while waiting for `" + String.join(" ", command) + "`", e);
		}
		return new CommandResult(p.exitValue(), out, err.toString());
	}

	private static void drain(InputStream in, StringBuilder into) {
		try (in) {
			byte[] bytes = in.readAllBytes();
			synchronized (into) {
				into.append(new String(bytes, StandardCharsets.UTF_8));
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
