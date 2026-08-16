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

package org.springaicommunity.claude.agent.sdk.sessions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliDiscovery;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Lists Claude Code <em>local</em> sessions — the sessions the CLI itself knows about on
 * this machine — via {@code claude agents --json}, the local counterpart of
 * {@link ClaudeCloudSessions}.
 *
 * <p>
 * The listing is the CLI's <em>agent view</em> ({@code claude agents}): every session
 * with a live process — interactive terminals and background agents, including background
 * agents that finished but are still resident (state {@code done}, status {@code idle}) —
 * and, with {@link #listLocalSessions(boolean) includeCompleted}, exited sessions as well
 * ({@code --all}, "the full agent view list"). Historical sessions whose transcripts are
 * only on disk are the {@code transcript} package's territory
 * ({@code TranscriptDirectory}); this class reports what the CLI's supervisor reports.
 * </p>
 *
 * <p>
 * Because the wire format is produced by the CLI and evolves with it,
 * {@link LocalSession} pairs typed accessors for every field observed on the wire (Claude
 * CLI 2.1.210) with {@link LocalSession#allValues()}, a flattened {@code path -> string}
 * map of the raw JSON entry in which unknown/new fields are preserved.
 * </p>
 *
 * <h2>Example</h2> <pre>{@code
 * for (ClaudeLocalSessions.LocalSession s : ClaudeLocalSessions.listLocalSessions(true)) {
 *     System.out.printf("%-11s %-8s %s in %s%n",
 *             s.kind(), s.state() != null ? s.state() : "-", s.sessionId(), s.cwd());
 * }
 * }</pre>
 *
 * <p>
 * All operations shell out to the {@code claude} CLI (located via
 * {@link ClaudeCliDiscovery}) and are point-in-time — re-query for fresh state.
 * </p>
 */
public final class ClaudeLocalSessions {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Duration CLI_TIMEOUT = Duration.ofSeconds(60);

	/** {@code state} values after which a session will not progress further. */
	private static final Set<String> TERMINAL_STATES = Set.of("done", "completed", "complete", "failed", "error",
			"errored", "stopped", "killed", "cancelled", "canceled");

	private ClaudeLocalSessions() {
	}

	/**
	 * One local session, as printed by {@code claude agents --json}.
	 *
	 * <p>
	 * Typed accessors cover every field observed on the wire as of Claude CLI 2.1.210;
	 * any field not listed here (including future additions) is still reachable through
	 * {@link #allValues()}, a flattened {@code path -> string} map of the raw JSON entry
	 * (nested objects as {@code "a.b"}, array elements as {@code "tags.0"}). JSON
	 * {@code null} leaves are rendered as the string {@code "null"}; empty objects/arrays
	 * contribute no entries.
	 * </p>
	 *
	 * <p>
	 * Field presence varies by entry kind and lifecycle:
	 * </p>
	 * <ul>
	 * <li>interactive, live: {@code pid}, {@code cwd}, {@code kind}, {@code startedAt},
	 * {@code sessionId}, {@code name}</li>
	 * <li>background, running: adds {@code id} and {@code state} (no {@code pid} until
	 * the worker process is up)</li>
	 * <li>background, finished but resident: adds {@code status} (e.g. {@code "idle"})
	 * alongside {@code state} (e.g. {@code "done"}) and {@code pid}</li>
	 * <li>background, exited (only with {@code --all}): {@code pid} and {@code status}
	 * disappear, {@code state} remains</li>
	 * </ul>
	 *
	 * @param id the short id used by {@code claude attach}/{@code logs}/{@code stop}
	 * (background entries only), or {@code null}
	 * @param sessionId the full session id — the transcript filename
	 * @param name a short label: the terminal-tab style name for interactive sessions
	 * (e.g. {@code "claude-agent-sdk-java-01"}), a task-derived label for background
	 * agents
	 * @param cwd the working directory the session runs in
	 * @param kind {@code "interactive"} or {@code "background"}
	 * @param startedAt when the session started (wire format: epoch milliseconds), or
	 * {@code null}
	 * @param state background lifecycle: {@code "working"}, {@code "blocked"},
	 * {@code "done"}, {@code "failed"}, {@code "stopped"}; {@code null} on interactive
	 * entries
	 * @param status finer-grained activity (e.g. {@code "idle"} on a finished-but-still-
	 * resident background agent), or {@code null}
	 * @param pid the OS process id while the session's process is alive, or {@code null}
	 * (e.g. exited entries)
	 * @param allValues flattened raw JSON of this entry, all values as strings
	 */
	public record LocalSession(String id, String sessionId, String name, String cwd, String kind, Instant startedAt,
			String state, String status, Integer pid, Map<String, String> allValues) {

		/**
		 * @return whether this entry is a background agent.
		 */
		public boolean isBackground() {
			return "background".equals(kind);
		}

		/**
		 * @return whether this entry is an interactive session.
		 */
		public boolean isInteractive() {
			return "interactive".equals(kind);
		}

		/**
		 * Whether the session reached a terminal {@link #state()} ({@code done} /
		 * {@code failed} / {@code stopped} and their wire synonyms). Interactive entries
		 * carry no {@code state} and always return {@code false} — they only appear in
		 * the list while running.
		 * @return true if the session will not progress further
		 */
		public boolean isTerminal() {
			return state != null && TERMINAL_STATES.contains(state.toLowerCase(Locale.ROOT));
		}

	}

	/**
	 * Lists the local sessions with a live process (interactive terminals and background
	 * agents), i.e. {@code claude agents --json} without {@code --all}.
	 * @return sessions in the CLI's own order
	 * @throws IOException if the CLI cannot be located, fails, or prints unparsable
	 * output
	 */
	public static List<LocalSession> listLocalSessions() throws IOException {
		return listLocalSessions(false);
	}

	/**
	 * Lists local sessions known to the CLI.
	 * @param includeCompleted whether to also include exited/completed sessions — the
	 * full agent view list (passes {@code --all})
	 * @return sessions in the CLI's own order
	 * @throws IOException if the CLI cannot be located, fails, or prints unparsable
	 * output
	 */
	public static List<LocalSession> listLocalSessions(boolean includeCompleted) throws IOException {
		List<String> command = new ArrayList<>(List.of(claudeBinary(), "agents", "--json"));
		if (includeCompleted) {
			command.add("--all");
		}
		CommandResult r = run(command, CLI_TIMEOUT);
		if (r.exitCode() != 0) {
			throw new IOException(
					"`" + String.join(" ", command) + "` failed (exit " + r.exitCode() + "): " + errorDetail(r));
		}
		return parseSessions(r.stdout());
	}

	/**
	 * Parses the JSON array printed by {@code claude agents --json}. Exposed so callers
	 * can parse captured output (fixtures, logs) without touching the CLI.
	 * @param json the raw JSON array
	 * @return the parsed sessions, in array order
	 * @throws IOException if the input is not a JSON array
	 */
	public static List<LocalSession> parseSessions(String json) throws IOException {
		JsonNode root = MAPPER.readTree(json);
		if (root == null || !root.isArray()) {
			throw new IOException("Expected a JSON array from `claude agents --json` but got: " + truncate(json, 300));
		}
		List<LocalSession> sessions = new ArrayList<>();
		for (JsonNode entry : root) {
			sessions.add(parseSession(entry));
		}
		return Collections.unmodifiableList(sessions);
	}

	// ------------------------------------------------------------------
	// Parsing
	// ------------------------------------------------------------------

	private static LocalSession parseSession(JsonNode n) {
		return new LocalSession(text(n, "id"), text(n, "sessionId"), text(n, "name"), text(n, "cwd"), text(n, "kind"),
				n.hasNonNull("startedAt") ? Instant.ofEpochMilli(n.get("startedAt").asLong()) : null, text(n, "state"),
				text(n, "status"), n.hasNonNull("pid") ? n.get("pid").asInt() : null, flatten(n));
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v == null || v.isNull() ? null : v.asText();
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

	/**
	 * Runs a CLI command keeping stdout and stderr separate — stdout must stay pure JSON
	 * for parsing, stderr is kept for diagnostics.
	 */
	private static CommandResult run(List<String> command, Duration timeout) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(command);
		Process p = pb.start();
		StringBuilder err = new StringBuilder();
		Thread errThread = new Thread(() -> drain(p.getErrorStream(), err), "local-sessions-stderr");
		errThread.setDaemon(true);
		errThread.start();
		String out;
		try (InputStream in = p.getInputStream()) {
			out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		boolean finished;
		try {
			finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
			throw new IOException("Interrupted while running: " + String.join(" ", command), e);
		}
		if (!finished) {
			p.destroyForcibly();
			throw new IOException("Timed out after " + timeout + " running: " + String.join(" ", command));
		}
		try {
			errThread.join(1000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return new CommandResult(p.exitValue(), out, err.toString());
	}

	private static void drain(InputStream in, StringBuilder sink) {
		try (InputStream s = in) {
			sink.append(new String(s.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String errorDetail(CommandResult r) {
		String s = r.stderr() != null && !r.stderr().isBlank() ? r.stderr() : r.stdout();
		return truncate(s, 500);
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		s = s.strip();
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}

	/** Captured result of a finished CLI process. */
	private record CommandResult(int exitCode, String stdout, String stderr) {
	}

}
