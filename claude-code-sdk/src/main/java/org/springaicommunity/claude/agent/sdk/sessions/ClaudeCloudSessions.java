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

package org.springaicommunity.claude.agent.sdk.sessions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client for the (undocumented) Claude Code cloud sessions API — the cloud counterpart
 * of {@link ClaudeLocalSessions}:
 *
 * <pre>
 *   GET https://api.anthropic.com/v1/code/sessions
 *   Authorization: Bearer &lt;claude.ai OAuth access token&gt;
 *   anthropic-version: 2023-06-01
 * </pre>
 *
 * This is the same endpoint the Claude Code CLI uses for {@code claude --teleport}.
 * Sessions are returned newest-activity-first (sorted by {@code last_event_at}
 * descending); pages are chained with a {@code ?cursor=<next_cursor>} query
 * parameter, mirroring the CLI's own pagination convention.
 *
 * <p>Authentication requires a <b>full-scope interactive login token</b> (scope
 * {@code user:sessions:claude_code}). Long-lived tokens minted by
 * {@code claude setup-token} / {@code CLAUDE_CODE_OAUTH_TOKEN} are inference-only
 * by design and are rejected with an authentication error.
 *
 * <p>Because the endpoint is undocumented, unknown/new fields are preserved in
 * {@link CloudSession#allValues()} as a flattened string map.
 */
public final class ClaudeCloudSessions {

	/** Overridable via {@code -Dclaude.sessions.baseUrl=...}; defaults to the first-party API. */
	private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

	private static final String SESSIONS_PATH = "/v1/code/sessions";
	private static final String ANTHROPIC_VERSION = "2023-06-01";
	private static final int MAX_PAGES = 1000;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	private ClaudeCloudSessions() {
	}

	// ------------------------------------------------------------------
	// Records
	// ------------------------------------------------------------------

	/**
	 * One cloud session, as returned by {@code GET /v1/code/sessions}.
	 *
	 * <p>Typed accessors cover every field observed on the wire; any field not
	 * listed here (including future additions) is still reachable through
	 * {@link #allValues()}, a flattened {@code path -> string} map of the raw
	 * JSON (e.g. {@code "config.sources.0.url"}, {@code "tags.0"},
	 * {@code "external_metadata.post_turn_summary.status_detail"}). JSON
	 * {@code null} leaves are rendered as the string {@code "null"}; empty
	 * objects/arrays contribute no entries.
	 *
	 * @param id                session id ({@code cse_...})
	 * @param title             human-readable session title
	 * @param status            lifecycle status: {@code "active"} or {@code "archived"}
	 * @param statusBucket      server-computed grouping, e.g. {@code "review_ready"},
	 *                          {@code "blocked"}, {@code "completed"}
	 * @param workerStatus      live activity state: {@code "idle"} (done, awaiting next
	 *                          instruction), {@code "requires_action"} (blocked on the user —
	 *                          permission prompt / plan approval), anything else = working
	 * @param connectionStatus  {@code "connected"} or {@code "disconnected"}
	 * @param environmentId     environment id ({@code env_...}); may be empty
	 * @param environmentKind   e.g. {@code "anthropic_cloud"}, {@code "bridge"}
	 * @param createdAt         session creation time
	 * @param lastEventAt       time of most recent event (the list sort key)
	 * @param unread            whether the session has unread activity
	 * @param userMessageCount  user message count (wire type is a string number)
	 * @param tags              tags such as {@code "remote-control-sdk"}
	 * @param participants      raw participant entries (shape not yet observed non-empty)
	 * @param relations         raw relation entries (shape not yet observed non-empty)
	 * @param config            launch configuration (model, sources, outcomes, ...)
	 * @param externalMetadata  worker-published metadata (post-turn summary, branches, ...)
	 * @param allValues         flattened raw JSON of this session, all values as strings
	 */
	public record CloudSession(
			String id,
			String title,
			String status,
			String statusBucket,
			String workerStatus,
			String connectionStatus,
			String environmentId,
			String environmentKind,
			Instant createdAt,
			Instant lastEventAt,
			boolean unread,
			long userMessageCount,
			List<String> tags,
			List<JsonNode> participants,
			List<JsonNode> relations,
			Config config,
			ExternalMetadata externalMetadata,
			Map<String, String> allValues) {

		/** True when the worker is done and waiting for the next instruction. */
		public boolean isIdle() {
			return "idle".equals(workerStatus);
		}

		/** True when the worker is blocked on the user (tool permission / plan approval). */
		public boolean requiresAction() {
			return "requires_action".equals(workerStatus);
		}
	}

	/**
	 * The session's launch configuration.
	 *
	 * @param model           model id, e.g. {@code "claude-opus-4-8"}
	 * @param effortLevel     effort level, e.g. {@code "max"} (absent on bridge sessions)
	 * @param origin          launching client: {@code "desktop_app"}, {@code "web_claude_ai"},
	 *                        {@code "ios"}, ... (absent on bridge sessions)
	 * @param mcpConnectorIds attached MCP connector ids
	 * @param sources         mounted inputs (git repositories, ...)
	 * @param outcomes        produced outputs (branches pushed, ...)
	 */
	public record Config(
			String model,
			String effortLevel,
			String origin,
			List<String> mcpConnectorIds,
			List<Source> sources,
			List<Outcome> outcomes) {
	}

	/**
	 * A mounted source, e.g. a git repository.
	 *
	 * @param type                e.g. {@code "git_repository"}
	 * @param url                 repository URL
	 * @param revision            optional ref, e.g. {@code "refs/heads/main"}
	 * @param sparseCheckoutPaths sparse-checkout paths, usually empty
	 */
	public record Source(
			String type,
			String url,
			String revision,
			List<String> sparseCheckoutPaths) {
	}

	/**
	 * A session outcome, e.g. a git repository the session pushed branches to.
	 *
	 * @param type    e.g. {@code "git_repository"}
	 * @param gitInfo git details when {@code type == "git_repository"}
	 */
	public record Outcome(
			String type,
			GitInfo gitInfo) {
	}

	/**
	 * Git details of an outcome.
	 *
	 * @param type     e.g. {@code "github"}
	 * @param host     git host; may be empty
	 * @param repo     owner/name, e.g. {@code "hooji/A1"}
	 * @param ref      optional ref
	 * @param branches branches created/pushed by the session
	 */
	public record GitInfo(
			String type,
			String host,
			String repo,
			String ref,
			List<String> branches) {
	}

	/**
	 * Worker-published metadata. All fields optional.
	 *
	 * @param containerCcVersion Claude Code version inside the container; may be empty
	 * @param lastServedModel    model that served the last turn
	 * @param model              model override, when present
	 * @param currentBranches    checkout-path -&gt; current branch
	 * @param postTurnSummary    worker's summary of where the session stands
	 */
	public record ExternalMetadata(
			String containerCcVersion,
			String lastServedModel,
			String model,
			Map<String, String> currentBranches,
			PostTurnSummary postTurnSummary) {
	}

	/**
	 * The worker's post-turn summary — very useful for notifications.
	 *
	 * @param needsAction    what the session needs from the user; may be empty
	 * @param statusCategory e.g. {@code "review_ready"}, {@code "need_input"}
	 * @param statusDetail   one-line human-readable state
	 */
	public record PostTurnSummary(
			String needsAction,
			String statusCategory,
			String statusDetail) {
	}

	/**
	 * One raw page of the list response.
	 *
	 * @param sessions    parsed sessions in server order (newest activity first)
	 * @param nextCursor  opaque cursor for the next page, or {@code null} on the last page;
	 *                    pass as {@code ?cursor=...}
	 * @param resumeToken opaque incremental-refresh token, or {@code null}
	 */
	public record Page(
			List<CloudSession> sessions,
			String nextCursor,
			String resumeToken) {
	}

	// ------------------------------------------------------------------
	// Listing
	// ------------------------------------------------------------------

	/**
	 * Lists all cloud sessions for the current machine's Claude Code login,
	 * paging through every result. Equivalent to
	 * {@code listCloudSessions(getClaudeOAuthToken(), false)}.
	 */
	public static List<CloudSession> listCloudSessions() throws IOException, InterruptedException {
		return listCloudSessions(getClaudeOAuthToken(), false);
	}

	/**
	 * Lists cloud sessions visible to the given OAuth access token.
	 *
	 * @param token         a claude.ai OAuth access token from an interactive Claude Code
	 *                      login (see {@link #getClaudeOAuthToken()}). Long-lived
	 *                      {@code claude setup-token} tokens are inference-only and will
	 *                      be rejected by the server.
	 * @param firstPageOnly when {@code true}, returns only the first page (the ~20 sessions
	 *                      with the most recent activity — what {@code claude --teleport}
	 *                      shows); when {@code false}, follows {@code next_cursor} until
	 *                      exhausted and returns all sessions.
	 * @return sessions in server order: {@code last_event_at} descending
	 */
	public static List<CloudSession> listCloudSessions(String token, boolean firstPageOnly)
			throws IOException, InterruptedException {
		List<CloudSession> all = new ArrayList<>();
		Set<String> seenIds = new HashSet<>();
		String cursor = null;

		for (int page = 0; page < MAX_PAGES; page++) {
			Page p = parsePage(fetchPage(token, cursor));
			for (CloudSession s : p.sessions()) {
				if (s.id() == null || seenIds.add(s.id())) {
					all.add(s);
				}
			}
			if (firstPageOnly || p.nextCursor() == null || p.nextCursor().isEmpty()) {
				return all;
			}
			if (p.nextCursor().equals(cursor)) {
				throw new IOException("pagination cursor did not advance after " + all.size()
						+ " sessions — the server may have changed its cursor parameter"
						+ " (currently sent as ?cursor=)");
			}
			cursor = p.nextCursor();
		}
		throw new IOException("gave up after " + MAX_PAGES + " pages (" + all.size() + " sessions)");
	}

	/**
	 * Parses one raw JSON page of the list response. Exposed so callers can
	 * parse captured responses (fixtures, logs) without touching the network.
	 */
	public static Page parsePage(String json) throws IOException {
		JsonNode root = MAPPER.readTree(json);
		List<CloudSession> sessions = new ArrayList<>();
		for (JsonNode item : root.path("data")) {
			sessions.add(parseSession(item));
		}
		return new Page(
				Collections.unmodifiableList(sessions),
				text(root, "next_cursor"),
				text(root, "resume_token"));
	}

	private static String fetchPage(String token, String cursor) throws IOException, InterruptedException {
		String url = baseUrl() + SESSIONS_PATH;
		if (cursor != null) {
			url += "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + token)
				.header("anthropic-version", ANTHROPIC_VERSION)
				.GET()
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			String hint = "";
			if (response.statusCode() == 401 || response.statusCode() == 403) {
				hint = " — cloud sessions require a full-scope interactive login token"
						+ " (scope user:sessions:claude_code); long-lived `claude setup-token`"
						+ " tokens are inference-only. If the token is from a login, it may"
						+ " have expired: run any `claude` command to refresh it.";
			}
			throw new IOException("GET " + url + " returned " + response.statusCode()
					+ hint + " body: " + truncate(response.body(), 300));
		}
		return response.body();
	}

	private static String baseUrl() {
		String base = System.getProperty("claude.sessions.baseUrl", DEFAULT_BASE_URL);
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	// ------------------------------------------------------------------
	// OAuth token helpers
	// ------------------------------------------------------------------

	/**
	 * Returns the current Claude Code OAuth access token for this machine.
	 * On macOS the token is read from the login Keychain (via the
	 * {@code security} command); on Linux it is read from
	 * {@code ~/.claude/.credentials.json}.
	 *
	 * <p>Access tokens are short-lived. If the stored token is already expired
	 * this method throws with a message saying so — running any {@code claude}
	 * command refreshes the stored credential.
	 */
	public static String getClaudeOAuthToken() throws IOException {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac") || os.contains("darwin")) {
			return getClaudeOAuthTokenMac();
		}
		return getClaudeOAuthTokenLinux(Path.of(System.getProperty("user.home"), ".claude"));
	}

	/**
	 * Linux variant: reads the token from {@code <claudeConfigDir>/.credentials.json}
	 * (the directory is normally {@code ~/.claude}, or {@code $CLAUDE_CONFIG_DIR}
	 * when set — useful for keeping multiple accounts side by side).
	 */
	public static String getClaudeOAuthTokenLinux(Path claudeConfigDir) throws IOException {
		Path credentials = claudeConfigDir.resolve(".credentials.json");
		if (!Files.isReadable(credentials)) {
			throw new IOException("Claude Code credentials not found/readable at " + credentials
					+ " — log in with `claude` first (or pass the right config dir).");
		}
		return extractAccessToken(Files.readString(credentials, StandardCharsets.UTF_8),
				credentials.toString());
	}

	/**
	 * macOS variant: runs
	 * {@code security find-generic-password -a <user> -s "Claude Code-credentials" -w}
	 * and extracts {@code claudeAiOauth.accessToken} from the JSON it prints.
	 */
	private static String getClaudeOAuthTokenMac() throws IOException {
		ProcessBuilder pb = new ProcessBuilder(
				"security", "find-generic-password",
				"-a", System.getProperty("user.name"),
				"-s", "Claude Code-credentials",
				"-w");
		Process process = pb.start();
		String stdout;
		String stderr;
		try {
			stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("`security find-generic-password` timed out");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("interrupted while reading the macOS Keychain", e);
		}
		if (process.exitValue() != 0) {
			throw new IOException("`security find-generic-password` failed (exit "
					+ process.exitValue() + "): " + truncate(stderr.trim(), 300)
					+ " — is Claude Code logged in on this machine?");
		}
		return extractAccessToken(stdout.trim(), "macOS Keychain item \"Claude Code-credentials\"");
	}

	private static String extractAccessToken(String credentialsJson, String source) throws IOException {
		JsonNode oauth = MAPPER.readTree(credentialsJson).path("claudeAiOauth");
		String token = oauth.path("accessToken").asText(null);
		if (token == null || token.isEmpty()) {
			throw new IOException("no claudeAiOauth.accessToken found in " + source);
		}
		JsonNode expiresAt = oauth.get("expiresAt");
		if (expiresAt != null && expiresAt.isNumber()) {
			long expiry = expiresAt.asLong();
			if (expiry > 0 && expiry < 100_000_000_000L) {
				expiry *= 1000; // tolerate epoch seconds; the CLI writes epoch millis
			}
			if (expiry > 0 && expiry < System.currentTimeMillis()) {
				throw new IOException("Claude Code OAuth token in " + source + " expired at "
						+ Instant.ofEpochMilli(expiry)
						+ " — run any `claude` command to refresh it, then retry.");
			}
		}
		return token;
	}

	// ------------------------------------------------------------------
	// Parsing
	// ------------------------------------------------------------------

	private static CloudSession parseSession(JsonNode n) {
		return new CloudSession(
				text(n, "id"),
				text(n, "title"),
				text(n, "status"),
				text(n, "status_bucket"),
				text(n, "worker_status"),
				text(n, "connection_status"),
				text(n, "environment_id"),
				text(n, "environment_kind"),
				instant(n, "created_at"),
				instant(n, "last_event_at"),
				n.path("unread").asBoolean(false),
				longValue(n.get("user_message_count")),
				stringList(n.get("tags")),
				nodeList(n.get("participants")),
				nodeList(n.get("relations")),
				parseConfig(n.get("config")),
				parseExternalMetadata(n.get("external_metadata")),
				flatten(n));
	}

	private static Config parseConfig(JsonNode n) {
		if (n == null || !n.isObject()) {
			return null;
		}
		List<Source> sources = new ArrayList<>();
		for (JsonNode s : n.path("sources")) {
			sources.add(new Source(
					text(s, "type"),
					text(s, "url"),
					text(s, "revision"),
					stringList(s.get("sparse_checkout_paths"))));
		}
		List<Outcome> outcomes = new ArrayList<>();
		for (JsonNode o : n.path("outcomes")) {
			outcomes.add(new Outcome(text(o, "type"), parseGitInfo(o.get("git_info"))));
		}
		return new Config(
				text(n, "model"),
				text(n, "effort_level"),
				text(n, "origin"),
				stringList(n.get("mcp_connector_ids")),
				Collections.unmodifiableList(sources),
				Collections.unmodifiableList(outcomes));
	}

	private static GitInfo parseGitInfo(JsonNode n) {
		if (n == null || !n.isObject()) {
			return null;
		}
		return new GitInfo(
				text(n, "type"),
				text(n, "host"),
				text(n, "repo"),
				text(n, "ref"),
				stringList(n.get("branches")));
	}

	private static ExternalMetadata parseExternalMetadata(JsonNode n) {
		if (n == null || !n.isObject()) {
			return null;
		}
		PostTurnSummary summary = null;
		JsonNode s = n.get("post_turn_summary");
		if (s != null && s.isObject()) {
			summary = new PostTurnSummary(
					text(s, "needs_action"),
					text(s, "status_category"),
					text(s, "status_detail"));
		}
		return new ExternalMetadata(
				text(n, "container_cc_version"),
				text(n, "last_served_model"),
				text(n, "model"),
				stringMap(n.get("current_branches")),
				summary);
	}

	// ------------------------------------------------------------------
	// JsonNode helpers
	// ------------------------------------------------------------------

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}

	private static Instant instant(JsonNode n, String field) {
		String v = text(n, field);
		if (v == null || v.isEmpty()) {
			return null;
		}
		try {
			return Instant.parse(v);
		} catch (DateTimeParseException e) {
			return null; // raw value is still available in allValues()
		}
	}

	private static long longValue(JsonNode v) {
		if (v == null || v.isNull()) {
			return 0;
		}
		if (v.isNumber()) {
			return v.asLong();
		}
		try {
			return Long.parseLong(v.asText().trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static List<String> stringList(JsonNode v) {
		if (v == null || !v.isArray()) {
			return List.of();
		}
		List<String> out = new ArrayList<>(v.size());
		for (JsonNode item : v) {
			out.add(item.isNull() ? null : item.asText());
		}
		return Collections.unmodifiableList(out);
	}

	private static List<JsonNode> nodeList(JsonNode v) {
		if (v == null || !v.isArray()) {
			return List.of();
		}
		List<JsonNode> out = new ArrayList<>(v.size());
		v.forEach(out::add);
		return Collections.unmodifiableList(out);
	}

	private static Map<String, String> stringMap(JsonNode v) {
		if (v == null || !v.isObject()) {
			return Map.of();
		}
		Map<String, String> out = new LinkedHashMap<>();
		v.properties().forEach(e ->
				out.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
		return Collections.unmodifiableMap(out);
	}

	/**
	 * Flattens a JSON tree into {@code dotted.path -> string} entries.
	 * Array elements use numeric path segments ({@code tags.0}); JSON null
	 * leaves become the string {@code "null"}; empty objects/arrays contribute
	 * no entries.
	 */
	private static Map<String, String> flatten(JsonNode node) {
		Map<String, String> out = new LinkedHashMap<>();
		flattenInto("", node, out);
		return Collections.unmodifiableMap(out);
	}

	private static void flattenInto(String prefix, JsonNode node, Map<String, String> out) {
		if (node.isObject()) {
			node.properties().forEach(e ->
					flattenInto(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(),
							e.getValue(), out));
		} else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				flattenInto(prefix + "." + i, node.get(i), out);
			}
		} else {
			out.put(prefix, node.isNull() ? "null" : node.asText());
		}
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		s = s.strip();
		return s.length() <= max ? s : s.substring(0, max) + "...";
	}
}
