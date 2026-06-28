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

package org.springaicommunity.claude.agent.sdk.background;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliDiscovery;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;

/**
 * Dispatches and manages Claude Code <em>background agents</em> — sessions started with
 * {@code claude --bg} that run detached and are monitored via {@code claude agents}.
 *
 * <p>Unlike the streaming {@code ClaudeSyncClient}/{@code ClaudeAsyncClient} (which attach to a
 * live session and read its output as it streams), a background agent follows a
 * <b>dispatch &rarr; poll &rarr; retrieve</b> model: {@link #dispatch(String) dispatch} returns
 * immediately with a {@link BackgroundAgent} handle, you {@link BackgroundAgent#awaitTerminal
 * poll} its {@link BackgroundAgentState state}, and you {@link BackgroundAgent#transcript
 * retrieve} the result from the on-disk transcript via the SDK's transcript toolkit.
 *
 * <p>All operations shell out to the {@code claude} CLI (located via
 * {@link ClaudeCliDiscovery}) and are point-in-time — re-query for fresh state.
 */
public final class BackgroundAgents {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Matches the {@code backgrounded · <shortId>} line of the dispatch banner. */
	private static final Pattern BANNER_ID = Pattern.compile("backgrounded\\b[^0-9a-fA-F]*([0-9a-fA-F]{8})");

	private static final Duration DISPATCH_TIMEOUT = Duration.ofMinutes(2);

	private static final Duration CLI_TIMEOUT = Duration.ofSeconds(60);

	private BackgroundAgents() {
	}

	/** Dispatches a background agent in the JVM's current directory with default options. */
	public static BackgroundAgent dispatch(String prompt) throws IOException {
		return dispatch(prompt, null, null);
	}

	/** Dispatches a background agent in {@code workingDirectory} with default options. */
	public static BackgroundAgent dispatch(String prompt, String workingDirectory) throws IOException {
		return dispatch(prompt, workingDirectory, null);
	}

	/**
	 * Dispatches a background agent: runs {@code claude --bg <config flags> "<prompt>"}, which
	 * returns immediately, then resolves the new session via {@code claude agents --json}.
	 * @param prompt the task for the agent (must not be blank)
	 * @param workingDirectory the directory to run in (JVM current dir when {@code null})
	 * @param options config forwarded as flags (model, system prompt, tool allow/deny lists,
	 * permission mode, add-dirs, budget, agents, settings, extra args); {@code null} for defaults.
	 * Streaming-only options are ignored.
	 * @return a handle to the dispatched agent
	 * @throws IOException if the CLI fails or the new agent can't be resolved
	 */
	public static BackgroundAgent dispatch(String prompt, String workingDirectory, CLIOptions options)
			throws IOException {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("prompt must not be blank");
		}
		Path cwd = workingDirectory != null ? Path.of(workingDirectory) : Path.of(System.getProperty("user.dir"));
		if (!Files.isDirectory(cwd)) {
			throw new IllegalArgumentException("workingDirectory is not a directory: " + cwd);
		}
		String realCwd = cwd.toRealPath().toString();
		CommandResult r = run(buildDispatchCommand(claudeBinary(), prompt, options), realCwd, options, DISPATCH_TIMEOUT);
		if (r.exitCode() != 0) {
			throw new IOException("`claude --bg` failed (exit " + r.exitCode() + "): " + errorDetail(r));
		}
		String shortId = parseBannerId(r.stdout());
		BackgroundAgentStatus resolved = resolveDispatched(shortId, realCwd);
		if (resolved == null) {
			throw new IOException("Dispatched a background agent but could not resolve its id from the `claude --bg` "
					+ "output or `claude agents --json`. Output was:\n" + r.stdout().strip());
		}
		return new BackgroundAgent(resolved.id() != null ? resolved.id() : shortId, resolved.sessionId(),
				resolved.cwd() != null ? resolved.cwd() : realCwd);
	}

	/** @return the currently-active background agents (excludes completed ones). */
	public static List<BackgroundAgent> list() throws IOException {
		return list(false);
	}

	/**
	 * @param includeCompleted whether to include finished agents (passes {@code --all})
	 * @return the background agents known to the supervisor
	 */
	public static List<BackgroundAgent> list(boolean includeCompleted) throws IOException {
		return statuses(includeCompleted).stream()
			.filter(BackgroundAgentStatus::isBackground)
			.map(s -> new BackgroundAgent(s.id(), s.sessionId(), s.cwd()))
			.toList();
	}

	/**
	 * Looks up a background agent by its short id or full session id.
	 * @return the handle, or empty if no such agent is known
	 */
	public static Optional<BackgroundAgent> get(String id) throws IOException {
		return status(id).map(s -> new BackgroundAgent(s.id() != null ? s.id() : id, s.sessionId(), s.cwd()));
	}

	// --- package-private CLI operations (used by BackgroundAgent) -------------------------

	/** Runs {@code claude agents --json [--all]} and parses every entry (all kinds). */
	static List<BackgroundAgentStatus> statuses(boolean includeCompleted) throws IOException {
		List<String> cmd = new ArrayList<>(List.of(claudeBinary(), "agents", "--json"));
		if (includeCompleted) {
			cmd.add("--all");
		}
		CommandResult r = run(cmd, null, null, CLI_TIMEOUT);
		if (r.exitCode() != 0) {
			throw new IOException("`claude agents --json` failed (exit " + r.exitCode() + "): " + errorDetail(r));
		}
		JsonNode arr;
		try {
			arr = MAPPER.readTree(r.stdout());
		}
		catch (Exception e) {
			throw new IOException("Could not parse `claude agents --json` output: " + e.getMessage(), e);
		}
		List<BackgroundAgentStatus> out = new ArrayList<>();
		if (arr != null && arr.isArray()) {
			for (JsonNode n : arr) {
				out.add(BackgroundAgentStatus.from(n));
			}
		}
		return out;
	}

	/** @return the snapshot for the agent identified by short id or full session id. */
	static Optional<BackgroundAgentStatus> status(String id) throws IOException {
		return statuses(true).stream().filter(s -> matchesId(s, id)).findFirst();
	}

	/** Runs {@code claude logs <id>} (raw terminal output, ANSI included). */
	static String logs(String id) throws IOException {
		CommandResult r = run(List.of(claudeBinary(), "logs", id), null, null, CLI_TIMEOUT);
		if (r.exitCode() != 0) {
			throw new IOException("`claude logs " + id + "` failed (exit " + r.exitCode() + "): " + errorDetail(r));
		}
		return r.stdout();
	}

	/** Runs {@code claude stop <id>} (the conversation is kept). */
	static void stop(String id) throws IOException {
		CommandResult r = run(List.of(claudeBinary(), "stop", id), null, null, CLI_TIMEOUT);
		if (r.exitCode() != 0) {
			throw new IOException("`claude stop " + id + "` failed (exit " + r.exitCode() + "): " + errorDetail(r));
		}
	}

	// --- internals -----------------------------------------------------------------------

	/** Builds {@code claude --bg <flags> "<prompt>"}, forwarding the config-bearing options. */
	static List<String> buildDispatchCommand(String binary, String prompt, CLIOptions o) {
		List<String> c = new ArrayList<>();
		c.add(binary);
		c.add("--bg");
		if (o != null) {
			addOpt(c, "--model", o.getModel());
			addOpt(c, "--fallback-model", o.getFallbackModel());
			addOpt(c, "--system-prompt", o.getSystemPrompt());
			addOpt(c, "--append-system-prompt", o.getAppendSystemPrompt());
			if (o.getMaxThinkingTokens() != null) {
				c.add("--max-thinking-tokens");
				c.add(String.valueOf(o.getMaxThinkingTokens()));
			}
			addList(c, "--allowedTools", o.getAllowedTools());
			addList(c, "--disallowedTools", o.getDisallowedTools());
			if (o.getPermissionMode() != null) {
				c.add("--permission-mode");
				c.add(o.getPermissionMode().getValue());
			}
			addOpt(c, "--permission-prompt-tool", o.getPermissionPromptToolName());
			if (o.getAddDirs() != null) {
				for (String d : o.getAddDirs()) {
					c.add("--add-dir");
					c.add(d);
				}
			}
			addList(c, "--setting-sources", o.getSettingSources());
			addOpt(c, "--settings", o.getSettings());
			addOpt(c, "--agents", o.getAgents());
			if (o.getMaxTurns() != null) {
				c.add("--max-turns");
				c.add(String.valueOf(o.getMaxTurns()));
			}
			if (o.getMaxBudgetUsd() != null) {
				c.add("--max-budget-usd");
				c.add(String.valueOf(o.getMaxBudgetUsd()));
			}
			if (o.getExtraArgs() != null) {
				o.getExtraArgs().forEach((k, v) -> {
					c.add(k.startsWith("-") ? k : "--" + k);
					if (v != null && !v.isEmpty()) {
						c.add(v);
					}
				});
			}
		}
		c.add(prompt);
		return c;
	}

	/** Extracts the short id from the {@code backgrounded · <id>} dispatch banner. */
	static String parseBannerId(String banner) {
		if (banner == null) {
			return null;
		}
		Matcher m = BANNER_ID.matcher(banner);
		return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
	}

	private static BackgroundAgentStatus resolveDispatched(String shortId, String realCwd) throws IOException {
		List<BackgroundAgentStatus> all = statuses(true);
		if (shortId != null) {
			for (BackgroundAgentStatus s : all) {
				if (shortId.equals(s.id()) || (s.sessionId() != null && s.sessionId().startsWith(shortId))) {
					return s;
				}
			}
		}
		// Fallback: the most-recently-started background agent in this working directory.
		return all.stream()
			.filter(BackgroundAgentStatus::isBackground)
			.filter(s -> s.cwd() != null && sameDir(s.cwd(), realCwd))
			.max(Comparator.comparing(s -> s.startedAt() != null ? s.startedAt() : Instant.EPOCH))
			.orElse(null);
	}

	private static boolean matchesId(BackgroundAgentStatus s, String id) {
		return id.equals(s.id()) || id.equals(s.sessionId()) || (s.sessionId() != null && s.sessionId().startsWith(id));
	}

	private static boolean sameDir(String a, String b) {
		return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
	}

	private static void addOpt(List<String> c, String flag, String value) {
		if (value != null && !value.isBlank()) {
			c.add(flag);
			c.add(value);
		}
	}

	private static void addList(List<String> c, String flag, List<String> values) {
		if (values != null && !values.isEmpty()) {
			c.add(flag);
			c.add(String.join(",", values));
		}
	}

	private static String claudeBinary() {
		String p = ClaudeCliDiscovery.getDiscoveredPath();
		return p != null && !p.isBlank() ? p : "claude";
	}

	private static String errorDetail(CommandResult r) {
		String s = r.stderr() != null && !r.stderr().isBlank() ? r.stderr() : r.stdout();
		s = s == null ? "" : s.strip();
		return s.length() > 500 ? s.substring(0, 500) + "…" : s;
	}

	private static CommandResult run(List<String> command, String cwd, CLIOptions options, Duration timeout)
			throws IOException {
		ProcessBuilder pb = new ProcessBuilder(command);
		if (cwd != null) {
			pb.directory(Path.of(cwd).toFile());
		}
		if (options != null && options.getEnv() != null) {
			pb.environment().putAll(options.getEnv());
		}
		Process p = pb.start();
		StringBuilder err = new StringBuilder();
		Thread errThread = new Thread(() -> drain(p.getErrorStream(), err), "bg-agent-stderr");
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

	/** Captured result of a finished CLI process. */
	private record CommandResult(int exitCode, String stdout, String stderr) {
	}

}
