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
| **Transcript toolkit** (`transcript` package) | `TranscriptDirectory` loads every stored session for a working directory, recovers `--fork-session` lineage, replays history as SDK `Message`s with fork markers, and extracts referenced file paths. | [docs/session-history.md](docs/session-history.md) |
| **Session cloning** (`SessionClone`) | Clones a session into a new working directory — conversation *and* file state together — unlike `--fork-session`, which branches the conversation but shares one directory. | [docs/session-history.md](docs/session-history.md) |
| **Token-level streaming** (`StreamEvent`) | `partialTextStream()` / `partialEvents()` on the async client surface the CLI's `--include-partial-messages` deltas as they are generated. | [docs/partial-streaming.md](docs/partial-streaming.md) |
| **Fat-jar releases** | A `claude-code-sdk-all` uber jar (SDK + all runtime dependencies) published as a GitHub Release on every `v*` tag. | [docs/releasing.md](docs/releasing.md) |

## Requirements

- Java 17+
- Claude Code CLI installed and authenticated
- Maven 3.8+

## Installation

### Fat Jar (this fork's releases)

Each `v*` tag publishes a [GitHub Release](https://github.com/hooji/claude-agent-sdk-java/releases) with `claude-code-sdk-all-<version>.jar` — the SDK plus all runtime dependencies (Jackson, Reactor, zt-exec) and a NOP SLF4J binding, ready to drop on a classpath:

```bash
java -cp claude-code-sdk-all-1.2.0.jar:your-app.jar your.Main
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

then depend on `org.springaicommunity:claude-code-sdk:1.2.0` from your local repository.

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
        .workingDirectory(Path.of("."))
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
        .workingDirectory(Path.of("."))
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
        .workingDirectory(Path.of("."))
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
    .workingDirectory(Path.of("."))
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
    .workingDirectory(Path.of("."))
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
        .workingDirectory(Path.of("/path/you/see"))
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
TranscriptDirectory dir = TranscriptDirectory.forWorkingDirectory(Path.of("/path/you/see"));
System.out.println(dir.toMarkdown());    // conversation tree, forks, sub-agents

// Replay a session's full history as SDK Message objects
dir.replayMessages(sessionId).forEach(System.out::println);

// Clone a session: conversation AND working-directory file state together
SessionClone.Result clone = SessionClone.clone(sessionId,
    Path.of("/original/dir"), Path.of("/clone/dir"));
// resume it with: ClaudeClient.sync(CLIOptions.builder().resume(clone.sessionId()).build())
//                 .workingDirectory(clone.workingDirectory())...
```

Full details — storage layout, fork recovery, replay semantics, cloning vs `--fork-session` — in [docs/session-history.md](docs/session-history.md).

---

## Configuration Options

```java
// Via ClaudeClient builder
ClaudeSyncClient client = ClaudeClient.sync()
    .workingDirectory(Path.of("."))
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
    .workingDirectory(Path.of("."))
    .build();
```

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
│   ├── session-history.md    # Transcripts, fork recovery, replay, cloning
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

### Key Differences

1. **Reactive Streaming**: Java SDK uses [Project Reactor](https://projectreactor.io/) (Flux/Mono) for reactive streams, while Python uses async generators.

2. **Factory Pattern**: Java follows the MCP Java SDK pattern with `ClaudeClient.sync()` / `ClaudeClient.async()` factory methods.

3. **Iterator vs Iterable**: `ClaudeSyncClient.receiveResponse()` returns `Iterator<ParsedMessage>` (not `Iterable`), requiring `while (response.hasNext())` pattern.

4. **Type Safety**: Java SDK leverages sealed interfaces and pattern matching for message type handling.

## License

Apache License 2.0

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.
