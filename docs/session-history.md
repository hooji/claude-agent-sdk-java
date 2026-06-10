# Session History & Transcripts

Claude Code persists every session it runs — the full conversation, tool activity, and
bookkeeping — as JSONL transcript files on the machine where the CLI runs. This SDK can
locate, load, replay, and clone those transcripts, either standalone or directly from a
connected client.

Everything described here lives in the `org.springaicommunity.claude.agent.sdk.transcript`
package, plus the `TranscriptAware` interface implemented by both clients.

## Where Claude Code stores transcripts

Transcripts live under a **projects root**, one folder per working directory:

```
~/.claude/projects/
└── -Users-nat-myproject/                  ← one folder per working directory
    ├── 4b6f429e-....jsonl                 ← one file per session (named by session id)
    ├── 29efebea-....jsonl
    ├── agent-3f72c1d0-....jsonl           ← sub-agent sidechain sessions
    └── 4b6f429e-.../                      ← externalized tool results (optional)
```

- The projects root is `~/.claude/projects`, or `$CLAUDE_CONFIG_DIR/projects` when the
  `CLAUDE_CONFIG_DIR` environment variable is set. The SDK honors both (plus a
  `claude.config.dir` system property, mainly for tests) via
  `TranscriptDirectory.projectsRoot()`.
- The per-directory folder name is the working directory's path with **every
  non-alphanumeric character replaced by `-`** — verified against the CLI (v2.1.170):
  `/`, `.`, `_`, and spaces all map to `-`, and case is preserved.
- The path is **canonicalized first**: Claude Code resolves symlinks before sanitizing.
  A session run in `/Users/nat/shared/x` where `shared` is a symlink to
  `/Volumes/My Shared Files/shared` is stored under
  `-Volumes-My-Shared-Files-shared-x`, not `-Users-nat-shared-x`.

You never need to compute any of this yourself. Give the SDK the directory *you* ran
Claude in, and it resolves the storage folder:

```java
Path folder = TranscriptDirectory.projectsDirFor(Path.of("/Users/nat/shared/x"));
TranscriptDirectory dir = TranscriptDirectory.forWorkingDirectory(Path.of("/Users/nat/shared/x"));
```

`forWorkingDirectory` returns an **empty** `TranscriptDirectory` (no sessions) when the
directory has no transcripts yet — it does not throw.

## Reading history from a client

Both `ClaudeSyncClient` and `ClaudeAsyncClient` implement `TranscriptAware`:

```java
try (ClaudeSyncClient client = ClaudeClient.sync()
        .workingDirectory(Path.of("/path/you/see"))
        .build()) {

    client.connectText("Remember: the launch code is 7-4-1");

    String id = client.getCurrentSessionId();   // CLI-assigned id, e.g. "16d8b472-…"
    Session session = client.getSession();      // this session's transcript
    TranscriptDirectory all = client.getTranscriptDirectory();
}
```

**`getCurrentSessionId()`** returns the session id as observed on the wire — the CLI
stamps it on every system and result message, and the client captures it from the first
response onward. It returns `null` before the first response arrives. This matters with
`continueConversation(true)` or `resume(id)`: until the CLI responds, the live session's
id is genuinely unknown (resuming can assign a *new* id).

**`getSession()`** (no-arg) resolves in this order:

1. The session identified by `getCurrentSessionId()`, when known and on disk.
2. Otherwise the **most recently modified** session in the working directory — the same
   session the CLI's `--continue` would resume. This covers the
   `continueConversation(true)`-before-connecting case.
3. Otherwise `null` (no sessions on disk yet).

**`getSession(String id)`** loads one specific session, or `null` if it doesn't exist.

Two things to keep in mind:

- Results are **point-in-time snapshots** read from disk on each call. A live session's
  transcript grows as the conversation progresses; call again to see new messages.
- Loading reads **every** transcript in the directory into memory (required for fork
  recovery — see below). For directories with a long history this is not free; hold on to
  the returned `TranscriptDirectory` rather than re-fetching in a loop.

## The transcript model

`TranscriptDirectory.load(Path)` (or `forWorkingDirectory`) parses every `*.jsonl` file
into:

- **`Session`** — one transcript file: `sessionId()`, `file()`, `agentSession()` (is it an
  `agent-*` sidechain?), `entries()`, `messages()`, and the fork partition `segments()`.
- **`TranscriptEntry`** — one line of the file, kept **losslessly**: the structural fields
  (`uuid`, `parentUuid`, `type`, `timestamp`, …) are lifted out, the parsed SDK `Message`
  is attached when the line is a conversation message, and the complete original JSON is
  retained in `raw()`. `regenerate(Path)` can write everything back JSON-equivalently.
- **`ConversationFamily`** — independent conversations grouped by shared root, each with a
  `ForkNode` tree of who forked from whom, and where.

`entry.referencedFiles()` extracts the on-disk file paths a line references (attachments,
tool file operations — `filePath`/`filename`/`file_path` fields anywhere in its JSON).
Claude Code externalizes file content rather than inlining it, so this gives you paths to
read bytes from disk without them ever being held in the transcript.

## Fork recovery

`claude --fork-session` branches a conversation: the child session file gets a **copy of
the parent's entire history** (each message keeps its original `uuid` but is re-stamped
with the child's `sessionId`), then diverges. On disk, nothing links child to parent
explicitly.

The SDK recovers the lineage from the uuid sets: a fork's uuid set is a strict superset of
each ancestor's, so for every message the *origin* session is the loaded session with the
smallest uuid set containing it. Contiguous runs of the same origin become `ForkSegment`s:

```
Session C (23 messages):
  segment from A: msgs [0..5]    ← inherited from grandparent
  segment from B: msgs [6..13]   ← inherited from parent
  segment from C: msgs [14..22]  ← C's own messages
```

From the partition, `Session` derives `isFork()`, `rootSessionId()`, `parentSessionId()`,
and `forkPointIndex()`. `TranscriptDirectory.toMarkdown()` renders the whole directory as
a readable tree of conversations, forks, and sub-agent sessions.

## Replay

`replayMessages(sessionId)` (eager `List<Message>`) and `replay(sessionId)` (cold
`Flux<Message>`) emit a session's full history — root through leaf — in a form compatible
with live message handling:

- Conversation lines arrive as their parsed types (`UserMessage`, `AssistantMessage`, …).
- Every other line (`attachment`, `queue-operation`, `mode`, …) arrives as a
  `RawTranscriptMessage` carrying the raw type and JSON, so nothing is dropped and the
  consumer chooses what to surface.
- A `ForkMarker` is emitted at each fork boundary (parent id, child id, message index,
  sibling forks — enough to build branch navigation).
- A terminal `HistoryEnd` signals completion with the final message count.

```java
dir.replay(sessionId)
    .doOnNext(msg -> {
        switch (msg) {
            case ForkMarker fm -> ui.showBranchPoint(fm);
            case RawTranscriptMessage raw -> { /* usually hidden */ }
            case HistoryEnd end -> ui.historyLoaded(end.messageCount());
            default -> ui.render(msg);   // normal conversation messages
        }
    })
    .subscribe();
```

## Session cloning

`--fork-session` branches the *conversation* but both branches share one working
directory — so a fork's conversation can drift out of sync with the files on disk.

`SessionClone.clone(sessionId, sourceDir, targetDir)` instead duplicates the session like
a VM snapshot copy:

1. Copies the **entire working-directory tree** to `targetDir` (which must be empty or
   non-existent, and not inside `sourceDir`).
2. Re-homes a **copy of the transcript** under a fresh session id, rewriting every path
   reference from the source directory to the target.
3. Copies any externalized tool-result files alongside it.

```java
SessionClone.Result clone = SessionClone.clone(sessionId,
        Path.of("/work/original"), Path.of("/work/experiment"));

ClaudeSyncClient resumed = ClaudeClient.sync(
        CLIOptions.builder().resume(clone.sessionId()).build())
    .workingDirectory(clone.workingDirectory())
    .build();
```

The original and the clone then move forward on fully independent timelines. For this to
stay consistent, create clones through this API and resume them in their target directory
— don't fork a clone again via the CLI.

## Caveats

- **Local disk only.** These APIs read the transcripts of the machine the SDK runs on. If
  the CLI runs elsewhere (container, remote host), the history is on *that* machine.
- **Most-recent is best-effort.** With several sessions running concurrently in one
  directory, "most recently modified" may not be the one you mean — prefer
  `getSession(id)` when you have the id.
- **Flush timing.** The CLI writes the transcript asynchronously; immediately after a
  response there can be a brief window before the latest lines are on disk.
