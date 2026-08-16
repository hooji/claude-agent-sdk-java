# Configuration Options Reference

Every option the SDK accepts, what it does, and where it goes. Options enter the SDK
through three doors, all funneling into the same subprocess launch:

| Entry point | Options type | Meant for |
|-------------|--------------|-----------|
| `Query.text(...)` / `Query.execute(...)` | [`QueryOptions`](#queryoptions-the-one-shot-subset) | One-shot calls; a curated subset of the below |
| `ClaudeClient.sync()` / `ClaudeClient.async()` | [builder methods on the spec](#client-level-settings) | Multi-turn clients configured inline |
| `ClaudeClient.sync(CLIOptions)` / `async(CLIOptions)` / `Query.execute(prompt, CLIOptions)` | [`CLIOptions`](#clioptions-the-full-set) | The **full** option set, pre-built and reusable |

`CLIOptions` is the superset — every inline builder method on the client specs simply
fills the same field. Most options map 1:1 onto a `claude` CLI flag (noted per option);
the rest configure SDK-side behavior and never reach the CLI. For a CLI flag with no
dedicated option, use the [`extraArgs`](#extraargs) escape hatch.

```java
// Inline (common subset + client-level settings):
ClaudeSyncClient client = ClaudeClient.sync()
    .workingDirectory("/work/proj")
    .model("claude-sonnet-4-5-20250929")
    .allowedTools(List.of("Read", "Grep"))
    .build();

// Full control, reusable across clients:
CLIOptions options = CLIOptions.builder()
    .model("claude-sonnet-4-5-20250929")
    .permissionMode(PermissionMode.DEFAULT)
    .autocompact("200000")
    .forwardSubagentText(true)
    .build();
ClaudeSyncClient client = ClaudeClient.sync(options)
    .workingDirectory("/work/proj")
    .build();
```

---

## Client-level settings

Configured on the client spec (both `sync()`/`async()` variants), not part of
`CLIOptions` — they describe *how the SDK runs the process*, not what's on its command
line.

### `workingDirectory(String)` — **required**

The directory the Claude CLI process runs in. This determines what files the session
sees, which project `CLAUDE.md` loads, and — because Claude Code keys transcript storage
by working directory — where the session's on-disk history lives (see
[session-history.md](session-history.md)). `build()` throws without it.

### `timeout(Duration)` — default 10 minutes

How long the client waits for a response before giving up (applied per operation: the
connect and each subsequent query). This is the timeout that actually governs
`ClaudeClient`-built clients; see [`CLIOptions.timeout`](#timeout) for the subtle
difference in the `Query` path.

### `claudePath(String)` — default: auto-discovery

Absolute path to the `claude` executable. When unset, the SDK discovers it
(`ClaudeCliDiscovery`): `PATH` lookup plus the common install locations. Set this to pin
a specific installation (e.g. a version under test) or when the JVM's environment lacks
your shell's `PATH`.

### `hookRegistry(HookRegistry)`

Registers PreToolUse / PostToolUse callbacks that run in-process before/after each tool
invocation, able to observe, allow, or block it. See the hooks section of the README.
Requires nothing on the command line — the SDK negotiates the control channel itself.

### `includePartialMessages(boolean)` — async spec only

Also available inline on `ClaudeClient.async()` because token-level streaming is an
async-client feature; identical to
[`CLIOptions.includePartialMessages`](#includepartialmessages).

---

## CLIOptions: the full set

Built with `CLIOptions.builder()`. Grouped here by concern; each entry names the CLI
flag it maps to (or "SDK-side" when none).

### Quick reference

| Option | CLI flag | Default |
|--------|----------|---------|
| [`model`](#model) | `--model` | CLI's configured default |
| [`fallbackModel`](#fallbackmodel) | `--fallback-model` | none |
| [`maxThinkingTokens`](#maxthinkingtokens) | `--max-thinking-tokens` | CLI default |
| [`maxTokens`](#maxtokens) | *(not forwarded)* | none |
| [`systemPrompt`](#systemprompt) | `--system-prompt` | Claude Code's default prompt |
| [`appendSystemPrompt`](#appendsystemprompt) | `--append-system-prompt` | none |
| [`jsonSchema`](#jsonschema) | `--json-schema` | none |
| [`includePartialMessages`](#includepartialmessages) | `--include-partial-messages` | `false` |
| [`forwardSubagentText`](#forwardsubagenttext) | `--forward-subagent-text` | `false` |
| [`tools`](#tools) | `--tools` | `null` (CLI default set) |
| [`allowedTools`](#allowedtools) | `--allowedTools` | empty |
| [`disallowedTools`](#disallowedtools) | `--disallowedTools` | empty |
| [`permissionMode`](#permissionmode) | `--permission-mode` / `--dangerously-skip-permissions` | see entry |
| [`permissionPromptToolName`](#permissionprompttoolname) | `--permission-prompt-tool` | auto |
| [`toolPermissionCallback`](#toolpermissioncallback) | SDK-side | none |
| [`continueConversation`](#continueconversation) | `--continue` | `false` |
| [`resume`](#resume) | `--resume` | none |
| [`forkSession`](#forksession) | `--fork-session` | `false` |
| [`maxTurns`](#maxturns) | `--max-turns` | unlimited |
| [`maxBudgetUsd`](#maxbudgetusd) | `--max-budget-usd` | unlimited |
| [`autocompact`](#autocompact) | `--autocompact` | CLI default (`auto`) |
| [`timeout`](#timeout) | SDK-side | 2 minutes |
| [`addDirs`](#adddirs) | `--add-dir` | empty |
| [`settings`](#settings) | `--settings` | none |
| [`settingSources`](#settingsources) | `--setting-sources` | empty (no filesystem settings) |
| [`agents`](#agents) | `--agents` | none |
| [`plugins`](#plugins) | `--plugin-dir` | empty |
| [`mcpServers`](#mcpservers) | `--mcp-config` | empty |
| [`env`](#env) | SDK-side (process env) | empty |
| [`oauthToken`](#oauthtoken) | SDK-side (env `CLAUDE_CODE_OAUTH_TOKEN`) | none (ambient auth) |
| [`otelLogRawApiBodiesDirectory`](#otellograwapibodiesdirectory) | SDK-side (env vars) | off |
| [`user`](#user) | SDK-side (`sudo -u`) | none |
| [`maxBufferSize`](#maxbuffersize) | SDK-side | 1 MB |
| [`stderrHandler`](#stderrhandler) | SDK-side | log at warn |
| [`extraArgs`](#extraargs) | any | empty |
| [`interactive`](#interactive) | *(unused)* | `false` |

### Model & output

#### `model`

`--model` — the model for the session. Accepts a full model id
(`"claude-sonnet-4-5-20250929"`) or a CLI alias (`"sonnet"`, `"opus"`). Constants
`CLIOptions.MODEL_HAIKU` / `MODEL_SONNET` / `MODEL_OPUS` name recent ids. Unset means
the CLI's own configured default model.

#### `fallbackModel`

`--fallback-model` — model(s) to fall back to automatically when the primary model is
overloaded or unavailable. The CLI accepts a comma-separated list, tried in order, and
re-tries the primary at the start of each turn.

#### `maxThinkingTokens`

`--max-thinking-tokens` — cap on extended-thinking tokens per turn. Raising it gives the
model more room to reason before answering; lowering it trades depth for latency/cost.

#### `maxTokens`

Accepted for Python-SDK parity but **not currently forwarded to the CLI** — the CLI has
no `--max-tokens` flag; response length is governed by the model and turn structure. If
you need to influence output tokens, the CLI honors the
`CLAUDE_CODE_MAX_OUTPUT_TOKENS` environment variable, settable via [`env`](#env).

### Prompting

#### `systemPrompt`

`--system-prompt` — **replaces** Claude Code's entire default system prompt with yours.
The session loses the default tooling instructions, so use this only when you want full
control of the prompt; for additions, prefer `appendSystemPrompt`.

#### `appendSystemPrompt`

`--append-system-prompt` — appends text to the default system prompt, keeping Claude
Code's built-in behavior and layering your instructions on top ("Be concise", house
rules, etc.). The right choice 95% of the time.

### Structured output & streaming

#### `jsonSchema`

`--json-schema` — a JSON Schema (as a `Map<String,Object>`, serialized for you) the
final result must validate against. Turns the session into a structured-output call: the
CLI steers the model to produce output conforming to the schema.

#### `includePartialMessages`

`--include-partial-messages` — emit token-level partial events as the model generates,
instead of only whole messages at turn end. Enables
`partialTextStream()` / `partialEvents()` on the async client (see
[partial-streaming.md](partial-streaming.md)). Off, the partial streams stay empty; the
whole-message APIs work regardless.

#### `forwardSubagentText`

`--forward-subagent-text` — also stream text and thinking produced by *subagents* (the
Task tool's child sessions) into the message stream, as assistant/user messages carrying
`parent_tool_use_id` so they can be attributed to the spawning tool call. Without it,
subagent output is only visible summarized in the tool result. The CLI supports this
flag exactly in the stream-json mode the SDK always runs in.

### Tools

Three distinct levers: `tools` decides what exists, `allowedTools`/`disallowedTools`
decide what may run (and with what permission treatment).

#### `tools`

`--tools` — the **base set** of built-in tools available to the session. Three states:
`null` (default) leaves the CLI's default set; an empty list sends `--tools ""`,
disabling all built-in tools; a list (`List.of("Read","Edit","Bash")`) makes exactly
those available.

#### `allowedTools`

`--allowedTools` — tool-permission *allow* rules. Matching tool calls run without a
permission prompt. Rules can be bare tool names (`"Read"`) or pattern-scoped
(`"Bash(git *)"`).

#### `disallowedTools`

`--disallowedTools` — tool-permission *deny* rules, same syntax; matching calls are
refused outright. Deny wins over allow.

### Permissions

#### `permissionMode`

`--permission-mode <value>`, except `DANGEROUSLY_SKIP_PERMISSIONS` which maps to the
separate `--dangerously-skip-permissions` flag. The `PermissionMode` enum:

| Value | CLI value | Behavior |
|-------|-----------|----------|
| `DEFAULT` | `default` | Normal prompting for tool permissions (legacy alias of `manual`, still accepted) |
| `MANUAL` | `manual` | Same prompting behavior under its current CLI name |
| `ACCEPT_EDITS` | `acceptEdits` | File edits auto-approved; other tools still gated |
| `AUTO` | `auto` | The CLI's permission classifier decides per action: safe actions run unprompted, risky ones still surface (rules configurable via the `autoMode` settings section; inspect with `claude auto-mode config`) |
| `PLAN` | `plan` | Read-only planning; execution requires plan approval |
| `DONT_ASK` | `dontAsk` | Never prompts — anything that would need a prompt is denied automatically; only pre-approved actions run |
| `BYPASS_PERMISSIONS` | `bypassPermissions` | All permission checks bypassed |
| `DANGEROUSLY_SKIP_PERMISSIONS` | `--dangerously-skip-permissions` | Everything runs unprompted — sandboxed environments only |

For unattended sessions, `AUTO` (headed judgment without a human) and `DONT_ASK`
(deterministic deny) are the two purpose-built choices; `PLAN` blocks waiting for an
approval no one will give, so avoid it headless.

Defaults differ by path, so set it explicitly when it matters:
`CLIOptions.builder()` defaults to `BYPASS_PERMISSIONS` (headless-friendly), the
`ClaudeClient.sync()/async()` inline specs default to `DEFAULT`, and
`CLIOptions.defaultOptions()` uses `DANGEROUSLY_SKIP_PERMISSIONS`.

In an unattended session, a `DEFAULT`-mode permission prompt has nobody to answer it —
pair `DEFAULT` with a [`toolPermissionCallback`](#toolpermissioncallback) or hooks, or
choose a more permissive mode plus tool allow-lists.

#### `permissionPromptToolName`

`--permission-prompt-tool` — names the tool the CLI consults for permission decisions.
You rarely set this: when a `toolPermissionCallback` is configured the SDK auto-enables
the stdio variant for you.

#### `toolPermissionCallback`

SDK-side. A `TransportToolPermissionCallback` invoked for each tool call the CLI wants
to run; your code returns allow/deny (with an optional message). This is the
programmatic answer to permission prompts — the SDK wires the control channel
(auto-adding `--permission-prompt-tool stdio`).

### Session lifecycle

#### `continueConversation`

`--continue` — resume the **most recently modified** session of the working directory
instead of starting fresh. The live session's id is unknown until the first response
arrives (see `TranscriptAware.getCurrentSessionId()`).

#### `resume`

`--resume <sessionId>` — resume one specific session by id. Combine with
[session cloning / archives](session-history.md) to resume restored or duplicated
sessions.

#### `forkSession`

`--fork-session` — when resuming (`resume` / `continueConversation`), branch onto a
**new** session id instead of appending to the original: the child copies the parent's
history and diverges, leaving the original untouched. Note both branches share one
working directory; `SessionClone` exists precisely for when that isn't wanted.

#### `maxTurns`

`--max-turns` — hard cap on agentic turns (model responses) per query, a runaway
brake for autonomous work. Unset means no cap.

#### `maxBudgetUsd`

`--max-budget-usd` — hard cap on API spend for the session; the CLI stops when the
budget is exhausted. The other runaway brake, and the one to set on anything unattended.

#### `autocompact`

`--autocompact` — the auto-compact window size: `"auto"` (let the CLI decide) or a
token count between 100k and 1M (e.g. `"200000"`). When the conversation approaches this
window, the CLI compacts older history into a summary to stay within context. Smaller
values compact earlier (cheaper, lossier); larger values keep more verbatim history.

#### `timeout`

SDK-side; default 2 minutes. **Scope caveat:** this field is honored by the `Query`
one-shot path (`Query.execute(prompt, options)` forwards it as the operation timeout).
Clients built via `ClaudeClient.sync(options)/async(options)` do **not** read it — set
the client-level [`timeout(Duration)`](#timeoutduration--default-10-minutes) on the
spec instead (default 10 minutes).

### Context & configuration

#### `addDirs`

`--add-dir` (repeated per entry; `addDir(String)` appends one) — additional directories
the session's tools may access beyond the working directory. The sanctioned way to span
"this repo plus that sibling checkout".

#### `settings`

`--settings` — path to a settings JSON file (or an inline JSON string) merged into the
session's configuration: permissions rules, hooks, env, etc. — anything a
`settings.json` can hold.

#### `settingSources`

`--setting-sources` — which of the CLI's *filesystem* settings layers to load:
`"user"`, `"project"`, `"local"` (comma-joined). **The SDK default is an empty list —
no filesystem settings load at all** — so SDK sessions are hermetic by default rather
than picking up whatever the machine's `~/.claude/settings.json` or the project's
`.claude/settings.json` says. Opt layers in deliberately when you want them.

#### `agents`

`--agents` — JSON defining custom subagents for the session, e.g.
`{"reviewer": {"description": "Reviews code", "prompt": "You are a code reviewer"}}`.
The model can then delegate to them via the Task tool.

#### `plugins`

`--plugin-dir` (repeated per entry; `plugin(PluginConfig)` appends one) — load Claude
Code plugins from local directories for this session only.

#### `mcpServers`

`--mcp-config` — MCP servers for the session, as a map of server name →
`McpServerConfig` (`mcpServer(name, config)` adds one). The SDK writes the config to a
temp file, passes it to the CLI, and cleans it up on close. Tools appear to the model as
`mcp__<serverName>__<toolName>`. Covers external processes (stdio) and in-process SDK
servers alike — see the MCP section of the README.

### Process & environment

#### `env`

SDK-side — extra environment variables for the CLI process (`env(Map)` replaces,
`env(name, value)` adds one). Applied last, so they override anything the SDK sets
itself. For reference, the SDK-provided baseline: the parent process environment, plus
`ANTHROPIC_API_KEY` (when present in the JVM's environment), plus
`CLAUDE_CODE_ENTRYPOINT=sdk-java` and `CLAUDE_AGENT_SDK_JAVA_VERSION` for telemetry
identification.

#### `oauthToken`

SDK-side convenience over `env` — authenticates the CLI subprocess with a **long-lived
Claude OAuth token** by injecting it as `CLAUDE_CODE_OAUTH_TOKEN` (the documented
headless-authentication mechanism; mint one with `claude setup-token`, requires a
Claude subscription). Available on `CLIOptions.builder()` and on both
`ClaudeClient.sync()/async()` fluent specs; `null` is a no-op so the value can be
plumbed through unconditionally.

Two sharp edges, both inherent to the CLI: these tokens are *inference-only*
(`user:inference`/`user:profile` scopes) — they run models but are rejected by the
cloud-sessions API, which needs the short-lived interactive login token (see
[cloud-sessions.md](cloud-sessions.md)); and the CLI prefers `ANTHROPIC_API_KEY` over
an OAuth token when both reach the process. A later explicit
`env("CLAUDE_CODE_OAUTH_TOKEN", ...)` overrides this option (last write wins).

#### `otelLogRawApiBodiesDirectory`

SDK-side convenience over `env` — points the CLI's `OTEL_LOG_RAW_API_BODIES` at a
directory (`file:` form), making it write the **untruncated** request/response JSON of
every Anthropic Messages API call as `*.request.json` / `*.response.json` file pairs.
Also sets the two prerequisites (`CLAUDE_CODE_ENABLE_TELEMETRY=1`,
`OTEL_LOGS_EXPORTER=console`) so it works out of the box; override via `env(...)`
afterward if you already export telemetry elsewhere. Invaluable for debugging what
actually went over the wire.

#### `user`

SDK-side — Unix username to run the CLI as; the SDK wraps the command in
`sudo -u <user>`. Requires sudoers configuration; ignored (with a warning) on Windows.
Useful for privilege separation of the CLI process from the JVM.

#### `maxBufferSize`

SDK-side; default 1 MB. Maximum bytes for a single JSON message parsed off the CLI's
stdout. Raise it if sessions produce very large single messages (giant tool results,
big structured outputs) and you see buffer-exceeded parse errors.

#### `stderrHandler`

SDK-side — a callback receiving each line the CLI writes to stderr (its diagnostics and
debug output). Without a handler, the SDK logs each line at warn level; install a
handler to surface, collect, or assert on them instead.

#### `extraArgs`

The escape hatch: arbitrary additional CLI flags, as flag-name → value (`null` value =
boolean flag, emitted as `--flag` alone; `extraArg(name, value)` adds one). Flag names
are passed **without** the `--` prefix. Use for CLI flags newer than the SDK — anything
here bypasses the SDK's knowledge, so no validation.

```java
CLIOptions.builder()
    .extraArg("effort", "high")        // --effort high
    .extraArg("safe-mode", null)       // --safe-mode
    .build();
```

#### `interactive`

Currently **unused** — retained for Python-SDK parity; no CLI flag is emitted and no SDK
behavior changes. The SDK always drives the CLI in bidirectional stream-json mode.

---

## QueryOptions: the one-shot subset

`QueryOptions` configures `Query.text(...)` / `Query.execute(...)` — the curated subset
that makes sense for a single prompt/response, plus the working directory (which for the
client APIs is a client-level setting):

`model`, `systemPrompt`, `appendSystemPrompt`, `timeout` (default 2 minutes — honored
here, unlike on client-built sessions), `allowedTools`, `disallowedTools`, `maxTurns`,
`maxBudgetUsd`, `workingDirectory` (defaults to the JVM's current directory),
`maxTokens`, `maxThinkingTokens`, `fallbackModel`, `jsonSchema` — each with exactly the
semantics documented above.

`QueryOptions.toCLIOptions()` converts to the full type; `Query.execute(prompt,
CLIOptions)` accepts the full type directly when the subset isn't enough.

---

## Related documentation

- [session-history.md](session-history.md) — transcripts, session labels (tags/titles), metadata, cloning, archives
- [partial-streaming.md](partial-streaming.md) — token-level streaming with `includePartialMessages`
- [background-agents.md](background-agents.md) — the `CLIOptions` subset honored by `claude --bg` dispatch
- README — API styles, hooks, MCP integration, permission callbacks
