# Background Agents

Claude Code can run a session as a **background agent** — `claude --bg "<prompt>"` dispatches a
detached, autonomous session that runs under a supervisor and returns immediately, to be
monitored with `claude agents`. The SDK wraps this as a **dispatch → poll → retrieve** API in
the `org.springaicommunity.claude.agent.sdk.background` package.

This is a different model from `ClaudeSyncClient`/`ClaudeAsyncClient`, which *attach* to a live
session and stream its output. A background agent is fire-and-forget: you get a handle back
immediately, poll its state, and read the result from the on-disk transcript once it finishes.

## Dispatch, await, retrieve

```java
BackgroundAgent agent = BackgroundAgents.dispatch(
        "Update the changelog for the latest release and summarize what changed.",
        Path.of("/work/myrepo"));          // returns immediately

String id = agent.id();                    // short id (e.g. "43a5daa7"), used by claude logs/stop
String sessionId = agent.sessionId();      // full session id (the transcript filename)

BackgroundAgentStatus done = agent.awaitTerminal(Duration.ofMinutes(30));  // poll state until terminal
assert done.state() == BackgroundAgentState.DONE;                          // or FAILED / STOPPED

Optional<String> answer = agent.result();  // final assistant text, from the transcript
Session transcript = agent.transcript().orElseThrow();   // full structured history (replayable)
```

- **`BackgroundAgents.dispatch(prompt[, workingDirectory, CLIOptions])`** runs `claude --bg`,
  forwarding the config-bearing options (model, system prompt, tool allow/deny lists, permission
  mode, add-dirs, budget, agents, settings, extra args), parses the `backgrounded · <id>` banner,
  and resolves the full record via `claude agents --json`.
- **`BackgroundAgents.list([includeCompleted])`** / **`get(id)`** enumerate or look up agents.

## The handle (`BackgroundAgent`)

| Method | What |
|--------|------|
| `status()` / `state()` | Live snapshot / lifecycle state from `claude agents --json`. |
| `awaitTerminal(timeout[, pollInterval])` | Poll until `DONE` / `FAILED` / `STOPPED`, or `TimeoutException`. |
| `transcript()` | The session's `Session` via the transcript toolkit — replayable, inspectable. |
| `result()` | The final assistant message text (read from the transcript). |
| `archiveTo(file)` | Package the finished agent (transcript + `.meta` metadata + working tree) as a `SessionArchive`. |
| `logs()` | Raw `claude logs` output (ANSI terminal capture) — prefer `transcript()`/`result()`. |
| `stop()` | `claude stop` — halts the agent; its conversation is kept. |

**`BackgroundAgentState`** is `WORKING`, `BLOCKED`, `DONE`, `FAILED`, `STOPPED`, or `UNKNOWN`
(`isTerminal()` is true for the last three).

## Why retrieval composes with the rest of the SDK

A background agent writes its transcript to the **normal** location
(`~/.claude/projects/<cwd>/<sessionId>.jsonl`), so `claude agents --json` hands the SDK exactly
what the transcript toolkit needs — `cwd` + `sessionId`. That's why `result()`, `transcript()`,
`replay()`, and `archiveTo()` all work on a finished agent with no extra plumbing: a background
run is just another session on disk. (The supervisor's own control state under
`~/.claude/jobs/<id>/` is an internal detail the SDK does not depend on.)

## Caveats

- **Local + CLI only.** Everything shells out to the `claude` CLI on the machine the SDK runs on,
  and reads transcripts from its `~/.claude`. A background agent dispatched elsewhere is monitored
  on *that* machine.
- **Autonomous execution.** A background agent runs on its own — set an appropriate
  `permissionMode` / tool allow-list (and `--max-budget-usd`) on the `CLIOptions` you dispatch
  with, the same as you would for any unattended run.
- **Streaming-only options are ignored** by `dispatch` (input/output format, partial messages,
  continue/resume) — they don't apply to the fire-and-forget model.
- **Flush timing.** `result()`/`transcript()` read from disk; immediately after dispatch (before
  the agent has produced output) they may be empty — `awaitTerminal` first.
