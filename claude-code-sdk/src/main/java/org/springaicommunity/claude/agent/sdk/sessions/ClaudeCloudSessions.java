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
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Client for the (undocumented) Claude Code cloud sessions API — the cloud counterpart of
 * {@link ClaudeLocalSessions}:
 *
 * <pre>
 *   GET https://api.anthropic.com/v1/code/sessions
 *   Authorization: Bearer &lt;claude.ai OAuth access token&gt;
 *   anthropic-version: 2023-06-01
 * </pre>
 *
 * This is the same endpoint the Claude Code CLI uses for {@code claude --teleport}.
 * Sessions are returned newest-activity-first (sorted by {@code last_event_at}
 * descending); pages are chained with a {@code ?cursor=<next_cursor>} query parameter,
 * mirroring the CLI's own pagination convention.
 *
 * <p>
 * Beyond listing, sessions can be relabeled the way the Claude apps do it:
 * {@link #updateSessionTags} ({@code PUT} with {@code add_tags}/{@code remove_tags} —
 * including the {@link #COLOR_TAG_PREFIX color:} convention behind the apps' color
 * labels) and {@link #updateSessionTitle}.
 *
 * <p>
 * Authentication requires a <b>full-scope interactive login token</b> (scope
 * {@code user:sessions:claude_code}). Long-lived tokens minted by
 * {@code claude setup-token} / {@code CLAUDE_CODE_OAUTH_TOKEN} are inference-only by
 * design and are rejected with an authentication error.
 *
 * <p>
 * Because the endpoint is undocumented, unknown/new fields are preserved in
 * {@link CloudSession#allValues()} as a flattened string map.
 */
public final class ClaudeCloudSessions {

	/**
	 * Overridable via {@code -Dclaude.sessions.baseUrl=...}; defaults to the first-party
	 * API.
	 */
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
	 * <p>
	 * Typed accessors cover every field observed on the wire; any field not listed here
	 * (including future additions) is still reachable through {@link #allValues()}, a
	 * flattened {@code path -> string} map of the raw JSON (e.g.
	 * {@code "config.sources.0.url"}, {@code "tags.0"},
	 * {@code "external_metadata.post_turn_summary.status_detail"}). JSON {@code null}
	 * leaves are rendered as the string {@code "null"}; empty objects/arrays contribute
	 * no entries.
	 *
	 * @param id session id ({@code cse_...})
	 * @param title human-readable session title
	 * @param status lifecycle status: {@code "active"} or {@code "archived"}
	 * @param statusBucket server-computed grouping, e.g. {@code "review_ready"},
	 * {@code "blocked"}, {@code "completed"}
	 * @param workerStatus live activity state: {@code "idle"} (done, awaiting next
	 * instruction), {@code "requires_action"} (blocked on the user — permission prompt /
	 * plan approval), anything else = working
	 * @param connectionStatus {@code "connected"} or {@code "disconnected"}
	 * @param environmentId environment id ({@code env_...}); may be empty
	 * @param environmentKind e.g. {@code "anthropic_cloud"}, {@code "bridge"}
	 * @param createdAt session creation time
	 * @param lastEventAt time of most recent event (the list sort key)
	 * @param unread whether the session has unread activity
	 * @param userMessageCount user message count (wire type is a string number)
	 * @param tags tags such as {@code "remote-control-sdk"}
	 * @param participants raw participant entries (shape not yet observed non-empty)
	 * @param relations raw relation entries (shape not yet observed non-empty)
	 * @param config launch configuration (model, sources, outcomes, ...)
	 * @param externalMetadata worker-published metadata (post-turn summary, branches,
	 * ...)
	 * @param allValues flattened raw JSON of this session, all values as strings
	 */
	public record CloudSession(String id, String title, String status, String statusBucket, String workerStatus,
			String connectionStatus, String environmentId, String environmentKind, Instant createdAt,
			Instant lastEventAt, boolean unread, long userMessageCount, List<String> tags, List<JsonNode> participants,
			List<JsonNode> relations, Config config, ExternalMetadata externalMetadata, Map<String, String> allValues) {

		/** True when the worker is done and waiting for the next instruction. */
		public boolean isIdle() {
			return "idle".equals(workerStatus);
		}

		/**
		 * True when the worker is blocked on the user (tool permission / plan approval).
		 */
		public boolean requiresAction() {
			return "requires_action".equals(workerStatus);
		}
	}

	/**
	 * The session's launch configuration.
	 *
	 * @param model model id, e.g. {@code "claude-opus-4-8"}
	 * @param effortLevel effort level, e.g. {@code "max"} (absent on bridge sessions)
	 * @param origin launching client: {@code "desktop_app"}, {@code "web_claude_ai"},
	 * {@code "ios"}, ... (absent on bridge sessions)
	 * @param mcpConnectorIds attached MCP connector ids
	 * @param sources mounted inputs (git repositories, ...)
	 * @param outcomes produced outputs (branches pushed, ...)
	 */
	public record Config(String model, String effortLevel, String origin, List<String> mcpConnectorIds,
			List<Source> sources, List<Outcome> outcomes) {
	}

	/**
	 * A mounted source, e.g. a git repository.
	 *
	 * @param type e.g. {@code "git_repository"}
	 * @param url repository URL
	 * @param revision optional ref, e.g. {@code "refs/heads/main"}
	 * @param sparseCheckoutPaths sparse-checkout paths, usually empty
	 */
	public record Source(String type, String url, String revision, List<String> sparseCheckoutPaths) {
	}

	/**
	 * A session outcome, e.g. a git repository the session pushed branches to.
	 *
	 * @param type e.g. {@code "git_repository"}
	 * @param gitInfo git details when {@code type == "git_repository"}
	 */
	public record Outcome(String type, GitInfo gitInfo) {
	}

	/**
	 * Git details of an outcome.
	 *
	 * @param type e.g. {@code "github"}
	 * @param host git host; may be empty
	 * @param repo owner/name, e.g. {@code "hooji/A1"}
	 * @param ref optional ref
	 * @param branches branches created/pushed by the session
	 */
	public record GitInfo(String type, String host, String repo, String ref, List<String> branches) {
	}

	/**
	 * Worker-published metadata. All fields optional.
	 *
	 * @param containerCcVersion Claude Code version inside the container; may be empty
	 * @param lastServedModel model that served the last turn
	 * @param model model override, when present
	 * @param currentBranches checkout-path -&gt; current branch
	 * @param postTurnSummary worker's summary of where the session stands
	 */
	public record ExternalMetadata(String containerCcVersion, String lastServedModel, String model,
			Map<String, String> currentBranches, PostTurnSummary postTurnSummary) {
	}

	/**
	 * The worker's post-turn summary — very useful for notifications.
	 *
	 * @param needsAction what the session needs from the user; may be empty
	 * @param statusCategory e.g. {@code "review_ready"}, {@code "need_input"}
	 * @param statusDetail one-line human-readable state
	 */
	public record PostTurnSummary(String needsAction, String statusCategory, String statusDetail) {
	}

	/**
	 * One raw page of the list response.
	 *
	 * @param sessions parsed sessions in server order (newest activity first)
	 * @param nextCursor opaque cursor for the next page, or {@code null} on the last
	 * page; pass as {@code ?cursor=...}
	 * @param resumeToken opaque incremental-refresh token, or {@code null}
	 */
	public record Page(List<CloudSession> sessions, String nextCursor, String resumeToken) {
	}

	// ------------------------------------------------------------------
	// Listing
	// ------------------------------------------------------------------

	/**
	 * Lists all cloud sessions for the current machine's Claude Code login, paging
	 * through every result. Equivalent to
	 * {@code listCloudSessions(getClaudeOAuthToken(), false)}.
	 */
	public static List<CloudSession> listCloudSessions() throws IOException, InterruptedException {
		return listCloudSessions(getClaudeOAuthToken(), false);
	}

	/**
	 * Lists cloud sessions visible to the given OAuth access token.
	 * @param token a claude.ai OAuth access token from an interactive Claude Code login
	 * (see {@link #getClaudeOAuthToken()}). Long-lived {@code claude setup-token} tokens
	 * are inference-only and will be rejected by the server.
	 * @param firstPageOnly when {@code true}, returns only the first page (the ~20
	 * sessions with the most recent activity — what {@code claude --teleport} shows);
	 * when {@code false}, follows {@code next_cursor} until exhausted and returns all
	 * sessions.
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
	 * Parses one raw JSON page of the list response. Exposed so callers can parse
	 * captured responses (fixtures, logs) without touching the network.
	 */
	public static Page parsePage(String json) throws IOException {
		JsonNode root = MAPPER.readTree(json);
		List<CloudSession> sessions = new ArrayList<>();
		for (JsonNode item : root.path("data")) {
			sessions.add(parseSession(item));
		}
		return new Page(Collections.unmodifiableList(sessions), text(root, "next_cursor"), text(root, "resume_token"));
	}

	/**
	 * Fetches one cloud session by id, using the current machine's Claude Code login.
	 * Equivalent to {@code getCloudSession(getClaudeOAuthToken(), sessionId)}.
	 * @param sessionId the session id ({@code cse_...})
	 * @return the session, or empty if no session with that id is visible to this login
	 */
	public static Optional<CloudSession> getCloudSession(String sessionId) throws IOException, InterruptedException {
		return getCloudSession(getClaudeOAuthToken(), sessionId);
	}

	/**
	 * Fetches one cloud session by id, via {@code GET /v1/code/sessions/<id>}.
	 *
	 * <p>
	 * Because the endpoint is undocumented, a {@code 404}/{@code 405} on the by-id path
	 * (which could mean either "no such session" or "no such endpoint shape") falls back
	 * to paging through the list and matching on {@link CloudSession#id()}, so the answer
	 * is definitive either way. Authentication errors are reported, not swallowed.
	 * </p>
	 * @param token a full-scope interactive login token (see
	 * {@link #getClaudeOAuthToken()})
	 * @param sessionId the session id ({@code cse_...})
	 * @return the session, or empty if no session with that id is visible to this token
	 */
	public static Optional<CloudSession> getCloudSession(String token, String sessionId)
			throws IOException, InterruptedException {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId must be non-blank");
		}
		String url = baseUrl() + SESSIONS_PATH + "/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(30))
			.header("Authorization", "Bearer " + token)
			.header("anthropic-version", ANTHROPIC_VERSION)
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 200) {
			return Optional.of(parseSessionJson(response.body()));
		}
		if (response.statusCode() == 404 || response.statusCode() == 405) {
			// Undocumented API: distinguish "session gone" from "endpoint shape changed"
			// by scanning the list, which is known-good.
			return findInList(token, sessionId);
		}
		throw new IOException("GET " + url + " returned " + response.statusCode() + authHint(response.statusCode())
				+ " body: " + truncate(response.body(), 300));
	}

	/**
	 * Parses one raw JSON session document — either a bare session object or one wrapped
	 * as {@code {"data": {...}}}. Package-visible for tests; the session-shaped
	 * counterpart of {@link #parsePage(String)}.
	 */
	static CloudSession parseSessionJson(String json) throws IOException {
		JsonNode root = MAPPER.readTree(json);
		JsonNode data = root.get("data");
		if (data != null && data.isObject()) {
			return parseSession(data);
		}
		return parseSession(root);
	}

	private static Optional<CloudSession> findInList(String token, String sessionId)
			throws IOException, InterruptedException {
		String cursor = null;
		for (int page = 0; page < MAX_PAGES; page++) {
			Page p = parsePage(fetchPage(token, cursor));
			for (CloudSession s : p.sessions()) {
				if (sessionId.equals(s.id())) {
					return Optional.of(s);
				}
			}
			if (p.nextCursor() == null || p.nextCursor().isEmpty() || p.nextCursor().equals(cursor)) {
				return Optional.empty();
			}
			cursor = p.nextCursor();
		}
		return Optional.empty();
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
			throw new IOException("GET " + url + " returned " + response.statusCode() + authHint(response.statusCode())
					+ " body: " + truncate(response.body(), 300));
		}
		return response.body();
	}

	private static String authHint(int statusCode) {
		if (statusCode == 401 || statusCode == 403) {
			return " — cloud sessions require a full-scope interactive login token"
					+ " (scope user:sessions:claude_code); long-lived `claude setup-token`"
					+ " tokens are inference-only. If the token is from a login, it may"
					+ " have expired: run any `claude` command to refresh it (or "
					+ "ClaudeCloudSessions.refreshOAuthToken()).";
		}
		return "";
	}

	private static String baseUrl() {
		String base = System.getProperty("claude.sessions.baseUrl", DEFAULT_BASE_URL);
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	// ------------------------------------------------------------------
	// Watching
	// ------------------------------------------------------------------

	/**
	 * Watches one cloud session and invokes {@code onTurnEnd} once, when the session
	 * finishes its turn, using this machine's Claude Code login and the default (minimum)
	 * 15-second polling interval. See
	 * {@link #watchForTurnEnd(String, String, Duration, Consumer, Consumer)}.
	 * @param sessionId the session id ({@code cse_...})
	 * @param onTurnEnd invoked with the session snapshot when its turn is over
	 * @return the live watch; close it to stop early
	 */
	public static CloudSessionWatch watchForTurnEnd(String sessionId, Consumer<CloudSession> onTurnEnd)
			throws IOException {
		return watchForTurnEnd(getClaudeOAuthToken(), sessionId, CloudSessionWatch.MIN_POLL_INTERVAL, onTurnEnd, null);
	}

	/**
	 * Watches one cloud session and invokes {@code onTurnEnd} once, when the session
	 * finishes its turn. See
	 * {@link #watchForTurnEnd(String, String, Duration, Consumer, Consumer)}.
	 */
	public static CloudSessionWatch watchForTurnEnd(String token, String sessionId, Duration pollInterval,
			Consumer<CloudSession> onTurnEnd) {
		return watchForTurnEnd(token, sessionId, pollInterval, onTurnEnd, null);
	}

	/**
	 * Watches one cloud session and invokes {@code onTurnEnd} <b>once</b>, when the
	 * session is observed to have finished its turn — {@code worker_status}
	 * {@code "idle"} (done, awaiting the next instruction) or {@code "requires_action"}
	 * (blocked on the user) — including immediately, if it is already in one of those
	 * states when the watch starts. The watch then closes itself.
	 *
	 * <p>
	 * The sessions API offers no push channel, so this polls
	 * {@link #getCloudSession(String, String)} on a daemon thread. To stay a good citizen
	 * against the undocumented API, intervals below
	 * {@link CloudSessionWatch#MIN_POLL_INTERVAL 15 seconds} are clamped up to 15
	 * seconds. Transient polling errors are reported to {@code onError} (or logged) and
	 * polling continues; the watch gives up (and reports) after
	 * {@value CloudSessionWatch#MAX_CONSECUTIVE_FAILURES} consecutive failures or when
	 * the session disappears. For long watches note the token itself expires after a few
	 * hours — {@link #refreshOAuthToken()} keeps it fresh.
	 * </p>
	 * @param token a full-scope interactive login token (see
	 * {@link #getClaudeOAuthToken()})
	 * @param sessionId the session id ({@code cse_...})
	 * @param pollInterval how often to poll (floored at 15s)
	 * @param onTurnEnd invoked with the session snapshot when its turn is over (runs on
	 * the polling thread)
	 * @param onError invoked for polling failures ({@code null} to just log them)
	 * @return the live watch; close it to stop early
	 */
	public static CloudSessionWatch watchForTurnEnd(String token, String sessionId, Duration pollInterval,
			Consumer<CloudSession> onTurnEnd, Consumer<Exception> onError) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId must be non-blank");
		}
		if (onTurnEnd == null) {
			throw new IllegalArgumentException("onTurnEnd callback is required");
		}
		Duration interval = (pollInterval == null || pollInterval.compareTo(CloudSessionWatch.MIN_POLL_INTERVAL) < 0)
				? CloudSessionWatch.MIN_POLL_INTERVAL : pollInterval;
		return new CloudSessionWatch(token, sessionId, interval, onTurnEnd, onError);
	}

	// ------------------------------------------------------------------
	// Updating (tags / title)
	// ------------------------------------------------------------------

	/**
	 * The tag-prefix convention the Claude apps use for a session's <em>color label</em>:
	 * the label is stored as an ordinary tag {@code "color:<name>"} (e.g.
	 * {@code "color:blue"}), at most one per session — setting a color removes any other
	 * {@code color:*} tag, and the "default" (no color) state is simply the absence of
	 * any such tag. Mirrors the CLI's own {@code SESSION_COLOR_TAG_PREFIX}.
	 */
	public static final String COLOR_TAG_PREFIX = "color:";

	/**
	 * Adds and/or removes tags on a cloud session, via {@code PUT /v1/code/sessions/<id>}
	 * with an {@code add_tags} / {@code remove_tags} body — the same incremental-update
	 * call the Claude desktop and mobile apps make when you label a session. Tags are
	 * free-form strings; by convention some carry structured prefixes (see
	 * {@link #COLOR_TAG_PREFIX}).
	 *
	 * <p>
	 * The current tags of a session are visible on {@link CloudSession#tags()}; this
	 * method needs only the deltas.
	 * @param token a full-scope interactive login token (see
	 * {@link #getClaudeOAuthToken()})
	 * @param sessionId the session id ({@code cse_...})
	 * @param addTags tags to add ({@code null} or empty for none)
	 * @param removeTags tags to remove ({@code null} or empty for none; removing an
	 * absent tag is a no-op server-side)
	 * @throws IllegalArgumentException if both lists are empty (nothing to change)
	 * @throws IOException if the request fails or the server rejects it
	 */
	public static void updateSessionTags(String token, String sessionId, List<String> addTags, List<String> removeTags)
			throws IOException, InterruptedException {
		putSession(token, sessionId, tagsUpdateBody(addTags, removeTags));
	}

	/**
	 * Renames a cloud session, via {@code PUT /v1/code/sessions/<id>} with a
	 * {@code {"title": ...}} body — the same call the Claude apps and the CLI's bridge
	 * make when a session is retitled.
	 * @param token a full-scope interactive login token (see
	 * {@link #getClaudeOAuthToken()})
	 * @param sessionId the session id ({@code cse_...})
	 * @param title the new title (non-blank)
	 * @throws IOException if the request fails or the server rejects it
	 */
	public static void updateSessionTitle(String token, String sessionId, String title)
			throws IOException, InterruptedException {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title must be non-blank");
		}
		putSession(token, sessionId, MAPPER.createObjectNode().put("title", title.trim()).toString());
	}

	/**
	 * Builds the {@code add_tags}/{@code remove_tags} update body. Package-private for
	 * tests; mirrors the CLI, which omits whichever list is empty.
	 */
	static String tagsUpdateBody(List<String> addTags, List<String> removeTags) {
		boolean hasAdds = addTags != null && !addTags.isEmpty();
		boolean hasRemoves = removeTags != null && !removeTags.isEmpty();
		if (!hasAdds && !hasRemoves) {
			throw new IllegalArgumentException("nothing to change: both addTags and removeTags are empty");
		}
		var body = MAPPER.createObjectNode();
		if (hasAdds) {
			var arr = body.putArray("add_tags");
			addTags.forEach(arr::add);
		}
		if (hasRemoves) {
			var arr = body.putArray("remove_tags");
			removeTags.forEach(arr::add);
		}
		return body.toString();
	}

	private static void putSession(String token, String sessionId, String body)
			throws IOException, InterruptedException {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("sessionId must be non-blank");
		}
		String url = baseUrl() + SESSIONS_PATH + "/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(30))
			.header("Authorization", "Bearer " + token)
			.header("anthropic-version", ANTHROPIC_VERSION)
			.header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("PUT " + url + " returned " + response.statusCode() + authHint(response.statusCode())
					+ " body: " + truncate(response.body(), 300));
		}
	}

	// ------------------------------------------------------------------
	// OAuth token helpers
	// ------------------------------------------------------------------

	/**
	 * The stored Claude Code OAuth credential of this machine's interactive login, as
	 * read from the Keychain (macOS) or {@code .credentials.json} (Linux) — the
	 * introspectable form of what {@link #getClaudeOAuthToken()} returns.
	 *
	 * <p>
	 * The refresh token deliberately stays unexposed: nothing in this SDK needs it, and
	 * it is the more dangerous half of the credential.
	 * </p>
	 *
	 * @param accessToken the short-lived OAuth access token
	 * @param expiresAt when the access token expires, or {@code null} if the credential
	 * carries no expiry information
	 * @param scopes the granted OAuth scopes (e.g. {@code user:inference},
	 * {@code user:sessions:claude_code}); empty if not recorded
	 * @param subscriptionType the plan behind the login (e.g. {@code "pro"},
	 * {@code "max"}), or {@code null} if not recorded
	 */
	public record OAuthCredentials(String accessToken, Instant expiresAt, List<String> scopes,
			String subscriptionType) {

		/**
		 * Whether the access token is still valid: present and not past its expiry (a
		 * credential without expiry information counts as valid).
		 * @return true if the token can still be used
		 */
		public boolean isValid() {
			return accessToken != null && !accessToken.isEmpty()
					&& (expiresAt == null || expiresAt.isAfter(Instant.now()));
		}

		/**
		 * How much lifetime the access token has left. Negative when the token is already
		 * expired (the magnitude says for how long).
		 * @return the remaining lifetime, or empty when the credential carries no expiry
		 * information
		 */
		public Optional<Duration> timeRemaining() {
			if (expiresAt == null) {
				return Optional.empty();
			}
			return Optional.of(Duration.between(Instant.now(), expiresAt));
		}

	}

	/**
	 * Returns the current Claude Code OAuth access token for this machine. On macOS the
	 * token is read from the login Keychain (via the {@code security} command); on Linux
	 * it is read from {@code ~/.claude/.credentials.json}.
	 *
	 * <p>
	 * Access tokens are short-lived. If the stored token is already expired this method
	 * throws with a message saying so — running any {@code claude} command refreshes the
	 * stored credential, or call {@link #refreshOAuthToken()} to have the SDK do it. To
	 * inspect rather than fail on expiry, use {@link #getClaudeOAuthCredentials()} /
	 * {@link #isOAuthTokenValid()} / {@link #oauthTokenTimeRemaining()}.
	 */
	public static String getClaudeOAuthToken() throws IOException {
		return requireUnexpired(getClaudeOAuthCredentials(), credentialsSourceDescription());
	}

	/**
	 * Linux variant: reads the token from {@code <claudeConfigDir>/.credentials.json}
	 * (the directory is normally {@code ~/.claude}, or {@code $CLAUDE_CONFIG_DIR} when
	 * set — useful for keeping multiple accounts side by side).
	 */
	public static String getClaudeOAuthTokenLinux(Path claudeConfigDir) throws IOException {
		Path credentials = claudeConfigDir.resolve(".credentials.json");
		return requireUnexpired(getClaudeOAuthCredentialsLinux(claudeConfigDir), credentials.toString());
	}

	/**
	 * Reads this machine's stored Claude Code OAuth credential — token plus expiry,
	 * scopes and subscription type — from the Keychain (macOS) or
	 * {@code ~/.claude/.credentials.json} (Linux). Unlike {@link #getClaudeOAuthToken()},
	 * an expired credential is returned, not thrown on: inspect it with
	 * {@link OAuthCredentials#isValid()} / {@link OAuthCredentials#timeRemaining()}.
	 * @return the stored credential
	 * @throws IOException if no credential is stored (not logged in) or it cannot be read
	 */
	public static OAuthCredentials getClaudeOAuthCredentials() throws IOException {
		return parseCredentials(readCredentialsJson(), credentialsSourceDescription());
	}

	/**
	 * Linux variant of {@link #getClaudeOAuthCredentials()} with an explicit config
	 * directory, for multi-account setups.
	 */
	public static OAuthCredentials getClaudeOAuthCredentialsLinux(Path claudeConfigDir) throws IOException {
		Path credentials = claudeConfigDir.resolve(".credentials.json");
		if (!Files.isReadable(credentials)) {
			throw new IOException("Claude Code credentials not found/readable at " + credentials
					+ " — log in with `claude` first (or pass the right config dir).");
		}
		return parseCredentials(Files.readString(credentials, StandardCharsets.UTF_8), credentials.toString());
	}

	/**
	 * Whether this machine's stored short-lived OAuth access token is still valid
	 * (present and not expired). A total function: any failure to read the credential —
	 * not logged in, unreadable file, malformed JSON — reports {@code false} rather than
	 * throwing.
	 * @return true if a usable token is stored right now
	 */
	public static boolean isOAuthTokenValid() {
		try {
			return getClaudeOAuthCredentials().isValid();
		}
		catch (IOException e) {
			return false;
		}
	}

	/**
	 * How much lifetime this machine's stored OAuth access token has left. Negative when
	 * the token is already expired (the magnitude says for how long).
	 * @return the remaining lifetime; empty when no credential is stored, it cannot be
	 * read, or it carries no expiry information
	 */
	public static Optional<Duration> oauthTokenTimeRemaining() {
		try {
			return getClaudeOAuthCredentials().timeRemaining();
		}
		catch (IOException e) {
			return Optional.empty();
		}
	}

	/**
	 * Ensures this machine's stored short-lived OAuth access token is fresh, invoking the
	 * Claude CLI to refresh it when needed, and returns the (possibly new) token.
	 * Equivalent to {@code refreshOAuthToken(Duration.ofMinutes(5))}.
	 */
	public static String refreshOAuthToken() throws IOException {
		return refreshOAuthToken(Duration.ofMinutes(5));
	}

	/**
	 * Ensures this machine's stored short-lived OAuth access token is valid for at least
	 * {@code minimumValidity}, and returns it. When the stored token already has that
	 * much lifetime left (or carries no expiry information), it is returned as-is without
	 * touching the CLI.
	 *
	 * <p>
	 * <b>How the refresh works:</b> the CLI has no dedicated "refresh" command — it
	 * refreshes the stored credential transparently (OAuth refresh-token exchange)
	 * whenever it makes an authenticated API request. So this method triggers exactly
	 * that: one minimal headless CLI turn ({@code claude -p ok --max-turns 1 --model
	 * haiku}), run with {@code ANTHROPIC_API_KEY} / {@code ANTHROPIC_AUTH_TOKEN} /
	 * {@code CLAUDE_CODE_OAUTH_TOKEN} stripped from its environment so the CLI must use
	 * (and therefore refresh) the stored subscription login rather than some other
	 * credential. This performs one tiny model call against your subscription. The result
	 * is then verified by re-reading the credential — if it is still not valid for
	 * {@code minimumValidity}, this method throws with the CLI's output rather than
	 * pretending success.
	 * </p>
	 * @param minimumValidity how much remaining lifetime counts as "fresh enough" to skip
	 * the refresh
	 * @return a stored access token valid for at least {@code minimumValidity} (best
	 * effort: freshly refreshed tokens are typically valid for hours)
	 * @throws IOException if not logged in, the CLI cannot be run, or the credential is
	 * still expired after the refresh attempt
	 */
	public static String refreshOAuthToken(Duration minimumValidity) throws IOException {
		OAuthCredentials current = getClaudeOAuthCredentials();
		if (current.isValid()
				&& current.timeRemaining().map(left -> left.compareTo(minimumValidity) >= 0).orElse(true)) {
			return current.accessToken();
		}
		String output = runCliRefreshInvocation();
		OAuthCredentials refreshed = getClaudeOAuthCredentials();
		if (!refreshed.isValid()) {
			throw new IOException("Claude Code OAuth token still expired after invoking the CLI to refresh it"
					+ " (expiry " + refreshed.expiresAt() + "). Is the refresh token itself expired? Re-login with"
					+ " `claude auth login`. CLI output: " + truncate(output, 500));
		}
		return refreshed.accessToken();
	}

	/**
	 * The minimal headless CLI invocation used by {@link #refreshOAuthToken(Duration)} to
	 * make the CLI touch (and thereby refresh) the stored login credential.
	 * Package-visible for tests.
	 */
	static List<String> refreshInvocationCommand(String claudePath) {
		return List.of(claudePath, "-p", "ok", "--max-turns", "1", "--model", "haiku");
	}

	private static String runCliRefreshInvocation() throws IOException {
		String claudePath;
		try {
			claudePath = org.springaicommunity.claude.agent.sdk.config.ClaudeCliDiscovery.discoverClaudePath();
		}
		catch (Exception e) {
			throw new IOException(
					"Cannot refresh the OAuth token: no Claude CLI found to invoke (" + e.getMessage() + ")", e);
		}
		ProcessBuilder pb = new ProcessBuilder(refreshInvocationCommand(claudePath));
		// Force the CLI onto the stored subscription login: higher-precedence
		// credentials in our environment would be used instead and nothing on disk
		// would refresh.
		pb.environment().remove("ANTHROPIC_API_KEY");
		pb.environment().remove("ANTHROPIC_AUTH_TOKEN");
		// pb.environment().remove("CLAUDE_CODE_OAUTH_TOKEN");
		pb.redirectErrorStream(true);
		Process process = pb.start();
		try {
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new IOException("Claude CLI refresh invocation timed out after 2 minutes");
			}
			return output;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("interrupted while waiting for the Claude CLI refresh invocation", e);
		}
	}

	/** Reads the raw credentials JSON from this machine's platform store. */
	private static String readCredentialsJson() throws IOException {
		if (isMac()) {
			return readCredentialsJsonMac();
		}
		Path credentials = Path.of(System.getProperty("user.home"), ".claude", ".credentials.json");
		if (!Files.isReadable(credentials)) {
			throw new IOException("Claude Code credentials not found/readable at " + credentials
					+ " — log in with `claude` first (or pass the right config dir).");
		}
		return Files.readString(credentials, StandardCharsets.UTF_8);
	}

	private static boolean isMac() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return os.contains("mac") || os.contains("darwin");
	}

	private static String credentialsSourceDescription() {
		return isMac() ? "macOS Keychain item \"Claude Code-credentials\""
				: Path.of(System.getProperty("user.home"), ".claude", ".credentials.json").toString();
	}

	/**
	 * macOS: runs
	 * {@code security find-generic-password -a <user> -s "Claude Code-credentials" -w}
	 * and returns the JSON it prints.
	 */
	private static String readCredentialsJsonMac() throws IOException {
		ProcessBuilder pb = new ProcessBuilder("security", "find-generic-password", "-a",
				System.getProperty("user.name"), "-s", "Claude Code-credentials", "-w");
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
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("interrupted while reading the macOS Keychain", e);
		}
		if (process.exitValue() != 0) {
			throw new IOException("`security find-generic-password` failed (exit " + process.exitValue() + "): "
					+ truncate(stderr.trim(), 300) + " — is Claude Code logged in on this machine?");
		}
		return stdout.trim();
	}

	/**
	 * Parses a {@code .credentials.json} document into an {@link OAuthCredentials}.
	 * Package-visible for tests. Tolerates {@code expiresAt} in epoch seconds (the CLI
	 * writes epoch milliseconds).
	 */
	static OAuthCredentials parseCredentials(String credentialsJson, String source) throws IOException {
		JsonNode oauth = MAPPER.readTree(credentialsJson).path("claudeAiOauth");
		String token = oauth.path("accessToken").asText(null);
		if (token == null || token.isEmpty()) {
			throw new IOException("no claudeAiOauth.accessToken found in " + source);
		}
		Instant expiresAt = null;
		JsonNode expiresAtNode = oauth.get("expiresAt");
		if (expiresAtNode != null && expiresAtNode.isNumber()) {
			long expiry = expiresAtNode.asLong();
			if (expiry > 0 && expiry < 100_000_000_000L) {
				expiry *= 1000; // tolerate epoch seconds; the CLI writes epoch millis
			}
			if (expiry > 0) {
				expiresAt = Instant.ofEpochMilli(expiry);
			}
		}
		return new OAuthCredentials(token, expiresAt, stringList(oauth.get("scopes")), text(oauth, "subscriptionType"));
	}

	/** The historical contract of the token getters: throw when expired, with a hint. */
	private static String requireUnexpired(OAuthCredentials credentials, String source) throws IOException {
		if (!credentials.isValid()) {
			throw new IOException("Claude Code OAuth token in " + source + " expired at " + credentials.expiresAt()
					+ " — run any `claude` command to refresh it (or ClaudeCloudSessions.refreshOAuthToken()),"
					+ " then retry.");
		}
		return credentials.accessToken();
	}

	// ------------------------------------------------------------------
	// Parsing
	// ------------------------------------------------------------------

	private static CloudSession parseSession(JsonNode n) {
		return new CloudSession(text(n, "id"), text(n, "title"), text(n, "status"), text(n, "status_bucket"),
				text(n, "worker_status"), text(n, "connection_status"), text(n, "environment_id"),
				text(n, "environment_kind"), instant(n, "created_at"), instant(n, "last_event_at"),
				n.path("unread").asBoolean(false), longValue(n.get("user_message_count")), stringList(n.get("tags")),
				nodeList(n.get("participants")), nodeList(n.get("relations")), parseConfig(n.get("config")),
				parseExternalMetadata(n.get("external_metadata")), flatten(n));
	}

	private static Config parseConfig(JsonNode n) {
		if (n == null || !n.isObject()) {
			return null;
		}
		List<Source> sources = new ArrayList<>();
		for (JsonNode s : n.path("sources")) {
			sources.add(new Source(text(s, "type"), text(s, "url"), text(s, "revision"),
					stringList(s.get("sparse_checkout_paths"))));
		}
		List<Outcome> outcomes = new ArrayList<>();
		for (JsonNode o : n.path("outcomes")) {
			outcomes.add(new Outcome(text(o, "type"), parseGitInfo(o.get("git_info"))));
		}
		return new Config(text(n, "model"), text(n, "effort_level"), text(n, "origin"),
				stringList(n.get("mcp_connector_ids")), Collections.unmodifiableList(sources),
				Collections.unmodifiableList(outcomes));
	}

	private static GitInfo parseGitInfo(JsonNode n) {
		if (n == null || !n.isObject()) {
			return null;
		}
		return new GitInfo(text(n, "type"), text(n, "host"), text(n, "repo"), text(n, "ref"),
				stringList(n.get("branches")));
	}

	private static ExternalMetadata parseExternalMetadata(JsonNode n) {
		if (n == null || !n.isObject()) {
			return null;
		}
		PostTurnSummary summary = null;
		JsonNode s = n.get("post_turn_summary");
		if (s != null && s.isObject()) {
			summary = new PostTurnSummary(text(s, "needs_action"), text(s, "status_category"),
					text(s, "status_detail"));
		}
		return new ExternalMetadata(text(n, "container_cc_version"), text(n, "last_served_model"), text(n, "model"),
				stringMap(n.get("current_branches")), summary);
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
		}
		catch (DateTimeParseException e) {
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
		}
		catch (NumberFormatException e) {
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
		v.properties().forEach(e -> out.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
		return Collections.unmodifiableMap(out);
	}

	/**
	 * Flattens a JSON tree into {@code dotted.path -> string} entries. Array elements use
	 * numeric path segments ({@code tags.0}); JSON null leaves become the string
	 * {@code "null"}; empty objects/arrays contribute no entries.
	 */
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
			return "";
		}
		s = s.strip();
		return s.length() <= max ? s : s.substring(0, max) + "...";
	}

}
