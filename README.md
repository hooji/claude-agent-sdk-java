# Claude Agent SDK for Java

Java SDK for interacting with [Claude Code CLI](https://docs.anthropic.com/en/docs/agents-and-tools/claude-code/overview). This is a pure Java implementation that mirrors the design of the official Python and TypeScript Claude Agent SDKs.

## Features

| Feature | Description |
|---------|-------------|
| **Simple One-Shot API** | `Query.text()` for quick answers in one line |
| **Blocking Client** | `ClaudeSyncClient` for multi-turn conversations with Iterator |
| **Reactive Client** | `ClaudeAsyncClient` with Flux/Mono for Spring WebFlux |
| **Session History** | Retrieve any session's on-disk transcript straight from a client |
| **Transcript Toolkit** | Load, replay, and analyze stored sessions, including fork lineage |
| **Session Cloning** | Duplicate a session's conversation *and* working directory together |
| **Token-Level Streaming** | Live partial-message deltas for typewriter-style UIs |
| **Hook System** | Register callbacks for tool use events |
| **MCP Integration** | Support for Model Context Protocol servers |
| **Permission Callbacks** | Programmatic control over tool execution |

## Documentation

- **[Tutorial](https://springaicommunity.mintlify.app/claude-agent-sdk/tutorial/index)** — Step-by-step guide covering all SDK features
- **[Tutorial Source Code](https://github.com/spring-ai-community/claude-agent-sdk-java-tutorial)** — Runnable examples for each tutorial module

The tutorial covers:

- All three API styles (Query, ClaudeSyncClient, ClaudeAsyncClient)
- Multi-turn conversations and session management
- Hooks, permission callbacks, and MCP integration
- Real-world patterns and best practices

Each module is a standalone runnable example with integration tests.

## What's New in This Fork

This repository is a fork of [spring-ai-community/claude-agent-sdk-java](https://github.com/spring-ai-community/claude-agent-sdk-java) that adds session-history tooling and packaging on top of the upstream 1.0.0 release:

| Addition | Summary | Details |
|----------|---------|---------|
| **Session history on the client** (`TranscriptAware`) | Open a client, then call `client.getSession()` / `client.getTranscriptDirectory()` to read the conversation history from disk. Clients now also capture the CLI-assigned session id (`getCurrentSessionId()`). | [docs/session-history.md](docs/session-history.md) |
| **Transcript toolkit** (`transcript` package) | `TranscriptDirectory` loads every stored session for a working directory, recovers `--fork-session` lineage, replays history as SDK `Message`s with fork markers, and extracts referenced file paths. A `dontLoadTranscripts` mode scans identity + metadata only, for a fast session browser. | [docs/session-history.md](docs/session-history.md) |
| **Session metadata** (`Session.metaData`) | Attach an arbitrary `Map<String,Serializable>` to any session, persisted as a `<sessionId>.meta` sidecar beside the transcript (`putMetaData` / `removeMetaData` / `writeMetaData`). Invisible to the CLI; managed entirely by the SDK. | [docs/session-history.md](docs/session-history.md) |
| **Official session labels** (`Session.tag` / `customTitle` / `aiTitle`) | Read *and set* the labels Claude Code itself keeps on a session — the tag behind the desktop app's **custom groups** ("Group by → Custom groups") and the `/resume` picker's grouping, plus the user-set and generated titles — stored as the CLI's own `{"type":"tag"}` / `{"type":"custom-title"}` transcript lines (`setTag` / `clearTag` / `setCustomTitle`). Cloud sessions get the counterpart `ClaudeCloudSessions.updateSessionTags` / `updateSessionTitle` (`add_tags`/`remove_tags` on `PUT /v1/code/sessions/{id}`, incl. the apps' `color:` tag convention). | [docs/session-history.md](docs/session-history.md) |
| **Session cloning** (`SessionClone`) | Clones a session into a new working directory — conversation, file state, metadata, task list, *and* the AI's persistent memory together — unlike `--fork-session`, which branches the conversation but shares one directory. | [docs/session-history.md](docs/session-history.md) |
| **Session archives** (`SessionArchive`) | Packages one session — transcript, its `.meta` metadata, the AI's persistent memory, its task list, *and* its whole working-directory tree — into a single portable ZIP, restorable to a new directory while keeping (or replacing) the session id. | [docs/session-history.md](docs/session-history.md) |
| **Background agents** (`BackgroundAgents`) | Dispatch a detached `claude --bg` agent and manage it (dispatch → poll → retrieve): `dispatch(...)` returns a handle, `awaitTerminal()` polls to completion, then `result()` / `transcript()` / `archiveTo()` retrieve the outcome via the transcript toolkit. | [docs/background-agents.md](docs/background-agents.md) |
| **Token-level streaming** (`StreamEvent`) | `partialTextStream()` / `partialEvents()` on the async client surface the CLI's `--include-partial-messages` deltas as they are generated. | [docs/partial-streaming.md](docs/partial-streaming.md) |
| **Fat-jar releases** | A `claude-code-sdk-all` uber jar (SDK + all runtime dependencies) published as a GitHub Release on every `v*` tag. | [docs/releasing.md](docs/releasing.md) |
| **Reliable async client shutdown** | `ClaudeAsyncClient.close()` is now a blocking `void` method instead of a cold `Mono<Void>` that silently did nothing unless subscribed — a common way to leak the Claude CLI subprocess. A JVM shutdown hook also force-closes the client (and terminates the CLI process) if the application exits without calling `close()`. | — |
| **Raw API body logging** (`CLIOptions.otelLogRawApiBodiesDirectory`) | Sets the CLI's `OTEL_LOG_RAW_API_BODIES` environment variable to `file:<directory>`, so the CLI writes untruncated request/response JSON for every Anthropic Messages API call into that directory. Also sets `CLAUDE_CODE_ENABLE_TELEMETRY=1` and `OTEL_LOGS_EXPORTER=console` (the other two prerequisites for this to actually produce output), overridable afterward via `env(...)` if you already export telemetry elsewhere. | — |
| **Cloud sessions monitor** (`ClaudeCloudSessions`) | Lists Claude Code **cloud** sessions (the `claude --teleport` set) via the undocumented `/v1/code/sessions` API, exposing the live `worker_status` (`idle` / `requires_action` / working) the teleport picker doesn't show — plus single-session fetch (`getCloudSession(id)`), a polling **turn-end watch** (`watchForTurnEnd`, callback when a session goes idle / needs you; ≥15s good-citizen polling), cursor pagination, a fully-typed `CloudSession` record with a flattened raw-value map, and OAuth token helpers: read (macOS Keychain / Linux `~/.claude/.credentials.json`), introspect (`isOAuthTokenValid()` / `oauthTokenTimeRemaining()` / `getClaudeOAuthCredentials()`), and refresh via the CLI (`refreshOAuthToken()`). Part of `claude-code-sdk` (and the fat jar). | [docs/cloud-sessions.md](docs/cloud-sessions.md) |
| **Account rate limits** (`ClaudeAccountRateLimits`) | Read the account's current claude.ai subscription rate limits — 5-hour / 7-day window utilization and reset times — via the CLI's **supported** stream-json `rate_limit_event` (no undocumented HTTP API). Standalone `fetch()` runs a minimal disposable Haiku probe session (~$0.002, 3–4s, no pre-existing session needed); connected `ClaudeSyncClient` / `ClaudeAsyncClient` sessions capture the same events for free, exposed as `client.latestRateLimit()` (plus a `rateLimitEvents()` Flux on the async client). Typed `RateLimitSnapshot` / `RateLimitInfo` / `RateLimitWindow` with the raw payload preserved. | [docs/account-rate-limits.md](docs/account-rate-limits.md) |
| **Auth status** (`ClaudeAuth`) | Read which Anthropic account the CLI is signed in as — `email`, `orgId`, `orgName`, `subscriptionType` on interactive claude.ai logins; auth state (`loggedIn`, `authMethod`, `apiProvider`) under token/API-key auth — via the supported `claude auth status --json` subcommand. Local and instant; typed `AuthStatus` record with the raw output preserved in `allValues()`. | [Auth Status](#auth-status) |
| **CLI version management** (`ClaudeCliVersions`) | Read the installed CLI version (`claude --version`), discover the newest version on the `stable` / `latest` / `next` release channels (npm dist-tags), compare them with `checkForUpdate()`, and trigger `claude update` — with an honest `wasUpdated()` before/after signal instead of the CLI's unreliable exit code. | [CLI Version Management](#cli-version-management) |
| **CLI installation** (`ClaudeCliInstaller`) | Detect whether the Claude CLI is present (`isInstalled()` / `installedPath()`), and install it from Anthropic's official native-installer script (`claude.ai/install.sh` / `.ps1`; `stable` / `latest` / pinned version) when it isn't — `ensureInstalled()` in one call, with the result verified by re-discovery instead of trusting the script's exit code. | [CLI Installation](#cli-installation) |
| **OAuth token injection** (`oauthToken(...)`) | First-class client/builder option for headless auth: injects a `claude setup-token` long-lived token as `CLAUDE_CODE_OAUTH_TOKEN` into the CLI subprocess. | [docs/options.md](docs/options.md) |
| **Local sessions listing** (`ClaudeLocalSessions`) | The local counterpart of `ClaudeCloudSessions`: lists the sessions the CLI itself knows about on this machine via `claude agents --json [--all]` — interactive terminals and background agents, live and completed — as a fully-typed `LocalSession` record with a flattened raw-value map that preserves future wire fields. | [Local CLI Sessions](#local-cli-sessions) |

## Requirements

- Java 17+
- Claude Code CLI installed and authenticated
- Maven 3.8+

## Installation

### Fat Jar (this fork's releases)

Each `v*` tag publishes a [GitHub Release](https://github.com/hooji/claude-agent-sdk-java/releases) with `claude-code-sdk-all-<version>.jar` — the SDK plus all runtime dependencies (Jackson, Reactor, zt-exec) and a NOP SLF4J binding, ready to drop on a classpath:

```bash
java -cp claude-code-sdk-all-1.1.2.jar:your-app.jar your.Main
```

A `-sources.jar` is attached for IDE source attachment. See [docs/releasing.md](docs/releasing.md) for how releases are cut.

### Maven Central (upstream 1.0.0)

The upstream project publishes to [Maven Central](https://central.sonatype.com/artifact/org.springaicommunity/claude-code-sdk). Note that `1.0.0` **predates the fork additions** described above (no transcript/history APIs, no partial streaming):

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>claude-code-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Building from Source

For the fork features as a regular (thin) Maven dependency, install locally:

```bash
git clone https://github.com/hooji/claude-agent-sdk-java.git
cd claude-agent-sdk-java
./mvnw install
```

then depend on `org.springaicommunity:claude-code-sdk:1.1.2` from your local repository.

## Three API Styles

| API | Class | Programming Style | Best For |
|-----|-------|-------------------|----------|
| **One-shot** | `Query` | Static methods | Simple scripts, CLI tools |
| **Blocking** | `ClaudeSyncClient` | Iterator-based | Traditional applications, synchronous workflows |
| **Reactive** | `ClaudeAsyncClient` | Flux/Mono | Non-blocking applications, high concurrency |

Both `ClaudeSyncClient` and `ClaudeAsyncClient` support the full feature set: multi-turn conversations, hooks, MCP integration, and permission callbacks. They differ only in programming paradigm (blocking vs non-blocking).

**Factory Pattern**: Use `ClaudeClient.sync()` or `ClaudeClient.async()` to create clients.

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        YOUR APPLICATION                          │
└───────────────┬─────────────────────┬─────────────────┬─────────┘
                │                     │                 │
                ▼                     ▼                 ▼
┌───────────────────┐   ┌───────────────────┐   ┌─────────────────┐
│      Query        │   │  ClaudeSyncClient │   │ ClaudeAsyncClient│
│   (one-shot)      │   │    (blocking)     │   │   (reactive)    │
│                   │   │                   │   │                 │
│  Query.text()     │   │  Iterator-based   │   │   Flux/Mono     │
│  Query.execute()  │   │  Multi-turn       │   │   Spring WebFlux│
└─────────┬─────────┘   └─────────┬─────────┘   └────────┬────────┘
          │                       │                      │
          └───────────────────────┼──────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                      StreamingTransport                          │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  • Subprocess management (Process API)                      ││
│  │  • JSON-LD streaming via stdin/stdout                       ││
│  │  • State machine: DISCONNECTED → CONNECTED → CLOSED         ││
│  │  • Thread-safe with separate schedulers                     ││
│  └─────────────────────────────────────────────────────────────┘│
└───────────────────────────────┬─────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Claude Code CLI                           │
│                   (claude --output-format stream-json)           │
└─────────────────────────────────────────────────────────────────┘
```

### Message Flow

```
┌──────────────┐          ┌─────────────────┐          ┌──────────┐
│  Your Code   │          │ StreamingTransport│          │ Claude   │
└──────┬───────┘          └────────┬────────┘          └────┬─────┘
       │                           │                        │
       │  connect("Hello")         │                        │
       │ ─────────────────────────>│ spawn process          │
       │                           │ ──────────────────────>│
       │                           │                        │
       │                           │    SystemMessage       │
       │                           │<───────────────────────│
       │   Iterator/Flux yields    │                        │
       │<──────────────────────────│    AssistantMessage    │
       │                           │<───────────────────────│
       │   process message...      │                        │
       │<──────────────────────────│    ResultMessage       │
       │                           │<───────────────────────│
       │   (turn complete)         │                        │
       │                           │                        │
       │  query("Follow-up")       │                        │
       │ ─────────────────────────>│ write to stdin         │
       │                           │ ──────────────────────>│
       │                           │                        │
       │   Iterator/Flux yields    │    AssistantMessage    │
       │<──────────────────────────│<───────────────────────│
       │                           │                        │
       │  close()                  │ terminate process      │
       │ ─────────────────────────>│ ──────────────────────>│
       │                           │                        │
       ▼                           ▼                        ▼
```

---

## API 1: Query (Simple One-Shot)

The simplest way to use Claude - one line of code:

```java
import org.springaicommunity.claude.agent.sdk.Query;

String answer = Query.text("What is 2+2?");
System.out.println(answer);  // "4"
```

### With Options

```java
String answer = Query.text("Explain quantum computing",
    QueryOptions.builder()
        .model("claude-sonnet-4-20250514")
        .appendSystemPrompt("Be concise")
        .timeout(Duration.ofMinutes(5))
        .build());
```

### Full Result with Metadata

```java
QueryResult result = Query.execute("Write a haiku about Java");
result.text().ifPresent(System.out::println);
System.out.println("Cost: $" + result.metadata().cost().calculateTotal());
System.out.println("Duration: " + result.metadata().getDuration().toMillis() + "ms");
```

---

## API 2: ClaudeSyncClient (Blocking/Iterator)

For multi-turn conversations, hooks, and MCP servers:

```java
import org.springaicommunity.claude.agent.sdk.ClaudeClient;
import org.springaicommunity.claude.agent.sdk.ClaudeSyncClient;

try (ClaudeSyncClient client = ClaudeClient.sync()
        .workingDirectory(".")
        .model("claude-sonnet-4-20250514")
        .build()) {

    // Simplest: just get the text (80% use case)
    String answer = client.connectText("What is 2+2?");
    System.out.println(answer);  // "4"

    // Follow-up with context preserved
    String followUp = client.queryText("Multiply that by 10");
    System.out.println(followUp);  // "40"
}
```

### Full Message Access (20% use case)

When you need message metadata, tool use details, or cost information:

```java
try (ClaudeSyncClient client = ClaudeClient.sync()
        .workingDirectory(".")
        .build()) {

    // For-each with good toString() on all message types
    for (Message msg : client.connectAndReceive("List files in current directory")) {
        System.out.println(msg);  // AssistantMessage, ResultMessage, etc.
    }
}
```

### With Hooks

```java
HookRegistry hookRegistry = new HookRegistry();

// Block dangerous commands
hookRegistry.registerPreToolUse("Bash", input -> {
    if (input instanceof HookInput.PreToolUseInput preToolUse) {
        String cmd = preToolUse.getArgument("command", String.class).orElse("");
        if (cmd.contains("rm -rf")) {
            return HookOutput.block("Dangerous command blocked");
        }
    }
    return HookOutput.allow();
});

try (ClaudeSyncClient client = ClaudeClient.sync()
        .workingDirectory(".")
        .permissionMode(PermissionMode.DEFAULT)
        .hookRegistry(hookRegistry)
        .build()) {
    // Hooks intercept tool calls
}
```

---

## API 3: ClaudeAsyncClient (Reactive)

For reactive applications using Project Reactor:

```java
ClaudeAsyncClient client = ClaudeClient.async()
    .workingDirectory(".")
    .model("claude-sonnet-4-20250514")
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .build();

// Stream text as it arrives
client.connect("Explain recursion").textStream()
    .doOnNext(System.out::print)
    .subscribe();
```

### Multi-Turn with flatMap Chaining

```java
client.connect("My favorite color is blue.").text()
    .doOnSuccess(System.out::println)
    .flatMap(r1 -> client.query("What is my favorite color?").text())
    .doOnSuccess(System.out::println)  // Claude remembers: "blue"
    .flatMap(r2 -> client.query("Spell it backwards").text())
    .doOnSuccess(System.out::println)  // "eulb"
    .subscribe();
```

### Full Message Access (20% use case)

When you need all message types (tool use, metadata, etc.):

```java
client.query("List files").messages()
    .doOnNext(System.out::println)  // Good toString() on all types
    .subscribe();
```

### Token-Level Streaming

For typewriter-style UIs, enable partial messages and stream incremental text deltas as they are generated (instead of whole `AssistantMessage`s):

```java
ClaudeAsyncClient client = ClaudeClient.async()
    .workingDirectory(".")
    .includePartialMessages(true)   // required: maps to --include-partial-messages
    .build();

client.connect("Write a haiku about Java").partialTextStream()
    .doOnNext(System.out::print)    // each token/delta as it arrives
    .subscribe();
```

`partialEvents()` exposes the raw `StreamEvent`s (thinking deltas, block boundaries) for advanced consumers. See [docs/partial-streaming.md](docs/partial-streaming.md).

---

## Session History & Transcripts

Claude Code stores every session's transcript on disk. Both clients implement `TranscriptAware`, so you can open a client and immediately read the history of the sessions in its working directory — you supply the directory *you* ran Claude in, and the SDK figures out the storage location (symlink canonicalization and path sanitization included):

```java
try (ClaudeSyncClient client = ClaudeClient.sync()
        .workingDirectory("/path/you/see")
        .build()) {

    String answer = client.connectText("Hello");

    client.getCurrentSessionId();        // the CLI-assigned session id
    Session session = client.getSession();  // this session's transcript (from disk)
    TranscriptDirectory all = client.getTranscriptDirectory();  // every session here
}
```

The `transcript` package also works standalone — no client needed:

```java
// All sessions for a working directory, with fork lineage recovered
TranscriptDirectory dir = TranscriptDirectory.forWorkingDirectory("/path/you/see");
System.out.println(dir.toMarkdown());    // conversation tree, forks, sub-agents

// Replay a session's full history as SDK Message objects
dir.replayMessages(sessionId).forEach(System.out::println);

// Clone a session: conversation AND working-directory file state together
SessionClone.Result clone = SessionClone.clone(sessionId,
    "/original/dir", "/clone/dir");
// resume it with: ClaudeClient.sync(CLIOptions.builder().resume(clone.sessionId()).build())
//                 .workingDirectory(clone.workingDirectory())...
```

Full details — storage layout, fork recovery, replay semantics, cloning vs `--fork-session` — in [docs/session-history.md](docs/session-history.md).

---

## Local CLI Sessions

`ClaudeLocalSessions` lists the sessions the Claude CLI itself knows about on this machine — the CLI's *agent view* (`claude agents --json`), covering interactive terminals and background agents. It is the local counterpart of the [cloud sessions monitor](docs/cloud-sessions.md) (`ClaudeCloudSessions`, in the same `sessions` package):

```java
import org.springaicommunity.claude.agent.sdk.sessions.ClaudeLocalSessions;

// Sessions with a live process (interactive + background)
for (var s : ClaudeLocalSessions.listLocalSessions()) {
    System.out.printf("%-11s %s in %s%n", s.kind(), s.sessionId(), s.cwd());
}

// The full agent view list, including exited/completed sessions (--all)
var all = ClaudeLocalSessions.listLocalSessions(true);
var finished = all.stream().filter(s -> s.isBackground() && s.isTerminal()).toList();
```

`LocalSession` types every field the CLI emits (as of 2.1.210): `id` (the short id `claude attach`/`logs`/`stop` take), `sessionId`, `name`, `cwd`, `kind`, `startedAt`, `state`, `status`, `pid` — and, like `ClaudeCloudSessions`'s `CloudSession`, keeps the *entire* raw entry in `allValues()`, a flattened `path -> string` map (`"meta.nested.deep"`, `"tags.0"`), so fields added by future CLI versions are preserved. `parseSessions(json)` is public for parsing captured output without touching the CLI.

Note the scope: this is the CLI supervisor's live/recent list, not the full on-disk history — for every transcript ever stored for a directory, use the `transcript` package (`TranscriptDirectory`).

---

## Auth Status

`ClaudeAuth` answers "which Anthropic account is this machine's Claude CLI signed in as" via the supported `claude auth status --json` subcommand — local and instant, no session started, no tokens consumed:

```java
import org.springaicommunity.claude.agent.sdk.config.ClaudeAuth;

ClaudeAuth.AuthStatus auth = ClaudeAuth.status();
if (auth.hasIdentity()) {
    System.out.printf("Signed in as %s (%s, %s plan)%n",
            auth.email(), auth.orgName(), auth.subscriptionType());
}
```

What comes back depends on the auth method: an interactive claude.ai login reports full identity (`email`, `orgId`, `orgName`, `subscriptionType`); injected-token auth (`CLAUDE_CODE_OAUTH_TOKEN`) and API keys report auth state only (`loggedIn`, `authMethod`, `apiProvider`) — `hasIdentity()` distinguishes the two. A logged-out CLI still parses (`loggedIn()` false). Like the other CLI-wrapping types, `AuthStatus` types every observed field and keeps the whole raw output in `allValues()`; `parseStatus(json)` is public for parsing captured output.

Because every SDK-spawned session authenticates through the same CLI resolution, this also identifies the account behind [`ClaudeAccountRateLimits.fetch()`](docs/account-rate-limits.md) results — unless a call overrides auth explicitly (`FetchOptions.oauthToken`, environment variables).

---

## Configuration Options

Every option — `CLIOptions`, the client-builder settings, and `QueryOptions` — is
documented with explanations and CLI-flag mappings in **[docs/options.md](docs/options.md)**.
A taste:

```java
// Via ClaudeClient builder
ClaudeSyncClient client = ClaudeClient.sync()
    .workingDirectory(".")
    .model("claude-sonnet-4-20250514")
    .systemPrompt("You are a helpful assistant")
    .permissionMode(PermissionMode.DEFAULT)
    .timeout(Duration.ofMinutes(5))
    .hookRegistry(hookRegistry)
    .build();

// Or via CLIOptions
CLIOptions options = CLIOptions.builder()
    .model("claude-sonnet-4-20250514")
    .permissionMode(PermissionMode.DEFAULT)
    .systemPrompt("You are a helpful assistant")
    .appendSystemPrompt("Be concise")
    .maxTurns(10)
    .allowedTools(List.of("Read", "Grep"))
    .disallowedTools(List.of("Bash"))
    .build();

ClaudeSyncClient client = ClaudeClient.sync(options)
    .workingDirectory(".")
    .build();
```

---

## CLI Version Management

`ClaudeCliVersions` surfaces the version information the interactive CLI shows in its UI — the installed version *and* the latest available one — so an application can decide for itself whether to update:

```java
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliVersions;
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliVersions.UpdateChannel;

String installed = ClaudeCliVersions.getInstalledVersion();          // "2.1.210" (runs `claude --version`)
String latest = ClaudeCliVersions.getLatestAvailableVersion();       // newest on the "latest" channel
String stable = ClaudeCliVersions.getLatestAvailableVersion(UpdateChannel.STABLE);

// Or both sides at once — the check-only mode the CLI itself doesn't have:
var check = ClaudeCliVersions.checkForUpdate();
if (check.isUpdateAvailable()) {
    var result = ClaudeCliVersions.update();                         // runs `claude update`
    if (result.wasUpdated()) {
        System.out.println("Updated " + result.previousVersion() + " -> " + result.currentVersion());
    } else {
        System.out.println("Update did not complete:\n" + result.output());
    }
}
```

Notes:

- **Latest-version source**: the npm registry [dist-tags](https://registry.npmjs.org/-/package/@anthropic-ai/claude-code/dist-tags) of `@anthropic-ai/claude-code` — the same `stable` / `latest` channel names that `claude install [target]` accepts and the CLI's `autoUpdatesChannel` setting selects. Override the registry with `-Dclaude.cli.registryUrl=...` (e.g. a corporate npm mirror).
- **`update()` honesty**: `claude update` has been observed to exit 0 even when it fails, so `UpdateResult.wasUpdated()` compares the installed version before and after instead of trusting the exit code; `output()` carries the CLI's own diagnostics.
- `compareVersions("2.1.10", "2.1.9")` is also exposed as a public semver-style comparator.

---

## CLI Installation

`ClaudeCliInstaller` closes the loop that `ClaudeCliVersions` opens: not just "is an update available?", but "is the CLI even here — and if not, put it here":

```java
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliInstaller;

if (!ClaudeCliInstaller.isInstalled()) {                  // probes PATH + common locations
    var result = ClaudeCliInstaller.install();            // official claude.ai/install.sh, latest channel
    System.out.println("Installed " + result.version() + " at " + result.path());
}

// or in one call — no-op when already present:
String claudePath = ClaudeCliInstaller.ensureInstalled().path();
```

Notes:

- **Install source**: Anthropic's official native-installer script (`https://claude.ai/install.sh`, `install.ps1` on Windows), run with a `stable` / `latest` / `"2.1.233"` target — the same channel names as `ClaudeCliVersions.UpdateChannel`. Override the download base with `-Dclaude.cli.installBaseUrl=...` for a corporate mirror.
- **Verified outcome**: after the script runs, the SDK re-runs CLI discovery and reads the version back — `InstallResult.path()`/`.version()` always describe a CLI that actually executes (the same don't-trust-the-exit-code stance as `ClaudeCliVersions.update()`).
- The native installer lands in `~/.local/bin/claude`, which discovery already probes — so a fresh install is usable immediately, no `PATH` change needed in the running JVM.

---

## Project Structure

```
claude-agent-sdk-java/
├── claude-code-sdk/          # Core SDK module
│   └── src/
│       ├── main/java/org/springaicommunity/claude/agent/sdk/
│       │   ├── Query.java              # Simple one-shot API
│       │   ├── ClaudeClient.java       # Factory: sync() / async()
│       │   ├── ClaudeSyncClient.java   # Blocking client interface
│       │   ├── ClaudeAsyncClient.java  # Reactive client interface
│       │   ├── TranscriptAware.java    # Session history access on clients
│       │   ├── transcript/             # TranscriptDirectory, Session, SessionClone
│       │   ├── usage/                  # ClaudeAccountRateLimits
│       │   ├── transport/              # StreamingTransport
│       │   ├── streaming/              # MessageStreamIterator
│       │   ├── hooks/                  # HookRegistry, HookCallback
│       │   ├── permission/             # ToolPermissionCallback
│       │   ├── mcp/                    # MCP server configuration
│       │   ├── types/                  # Message types, content blocks, StreamEvent
│       │   └── parsing/                # JSON parsing, control messages
│       └── test/
├── fatjar/                   # claude-code-sdk-all uber jar (GitHub Releases)
├── docs/                     # Deep-dive documentation
│   ├── options.md            # Every configuration option, explained
│   ├── session-history.md    # Transcripts, fork recovery, replay, cloning
│   ├── cloud-sessions.md     # Cloud sessions monitor (ClaudeCloudSessions)
│   ├── account-rate-limits.md # Account rate limits (ClaudeAccountRateLimits)
│   ├── partial-streaming.md  # Token-level streaming
│   └── releasing.md          # Release workflows and artifacts
└── examples/
    ├── hello-world/          # All three APIs demonstrated
    ├── email-agent/          # ClaudeAsyncClient with Vaadin UI
    ├── excel-demo/           # ClaudeAsyncClient streaming
    └── research-agent/       # ClaudeSyncClient multi-turn with hooks
```

---

## Python SDK Feature Comparison

The Java SDK mirrors the official [Python Claude Agent SDK](https://github.com/anthropics/claude-code-sdk-python). Current feature parity status:

| Feature | Python | Java | Notes |
|---------|:------:|:----:|-------|
| **Core APIs** | | | |
| One-shot queries | ✓ | ✓ | `Query.text()`, `Query.execute()` |
| Blocking client | ✓ | ✓ | `ClaudeClient.sync()` |
| Async client | ✓ | ✓ | `ClaudeClient.async()` (Reactor) |
| Multi-turn conversations | ✓ | ✓ | Context preserved across turns |
| **Configuration** | | | |
| Model selection | ✓ | ✓ | `.model()` or `CLIOptions` |
| System prompt | ✓ | ✓ | `.systemPrompt()` |
| Append system prompt | ✓ | ✓ | `.appendSystemPrompt()` |
| Permission modes | ✓ | ✓ | `PermissionMode` enum |
| Allowed/disallowed tools | ✓ | ✓ | `.allowedTools()`, `.disallowedTools()` |
| Max turns | ✓ | ✓ | `.maxTurns()` |
| Max tokens | ✓ | ✓ | `.maxTokens()` |
| **Extensibility** | | | |
| Hook system (PreToolUse) | ✓ | ✓ | `HookRegistry.registerPreToolUse()` |
| Hook system (PostToolUse) | ✓ | ✓ | `HookRegistry.registerPostToolUse()` |
| MCP server integration | ✓ | ✓ | External + in-process servers |
| Permission callbacks | ✓ | ✓ | `ToolPermissionCallback` |
| Agent definitions | ✓ | ✓ | `AgentDefinition` for subagents |
| **Advanced** | | | |
| Partial message streaming | ✓ | ✓ | `partialTextStream()` / `partialEvents()` |
| File checkpointing | ✓ | ✗ | Not yet implemented |
| Beta features (`--betas`) | ✓ | ✗ | Not yet implemented |
| Sandbox settings | ✓ | ✗ | Not yet implemented |
| **Java-only (this fork)** | | | |
| Session history on clients | ✗ | ✓ | `TranscriptAware`: `getSession()`, `getTranscriptDirectory()` |
| Transcript loading & replay | ✗ | ✓ | `TranscriptDirectory` with fork-lineage recovery |
| Session cloning | ✗ | ✓ | `SessionClone`: conversation + file state together |
| Session tags & titles (custom groups) | ✗ | ✓ | `Session.setTag()` / `setCustomTitle()`; cloud: `updateSessionTags` |

### Key Differences

1. **Reactive Streaming**: Java SDK uses [Project Reactor](https://projectreactor.io/) (Flux/Mono) for reactive streams, while Python uses async generators.

2. **Factory Pattern**: Java follows the MCP Java SDK pattern with `ClaudeClient.sync()` / `ClaudeClient.async()` factory methods.

3. **Iterator vs Iterable**: `ClaudeSyncClient.receiveResponse()` returns `Iterator<ParsedMessage>` (not `Iterable`), requiring `while (response.hasNext())` pattern.

4. **Type Safety**: Java SDK leverages sealed interfaces and pattern matching for message type handling.

## License

Apache License 2.0

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.
