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
    ├── 4b6f429e-....meta                  ← SDK session metadata sidecar (optional; written by this SDK)
    ├── 29efebea-....jsonl
    ├── agent-3f72c1d0-....jsonl           ← sub-agent sidechain sessions
    └── 4b6f429e-.../                      ← externalized tool results (optional)
```

The `.meta` sidecar is an **SDK convention**, not something Claude Code reads or writes — see
[Session metadata](#session-metadata). The CLI ignores it and leaves it untouched.

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

To enumerate **every** session on the machine rather than a single working directory,
`TranscriptDirectory.allUnder()` (or `allUnder(projectsRoot)`) returns one loaded
`TranscriptDirectory` per non-empty transcript folder under the projects root:

```java
for (TranscriptDirectory dir : TranscriptDirectory.allUnder()) {
    for (Session s : dir.mainSessions()) {
        // s.sessionId(), s.workingDirectory(), s.messages().size(), …
    }
}
```

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

- **`Session`** — one transcript file: `sessionId()`, `file()`, `workingDirectory()` (the `String`
  path recovered from the transcript's `cwd`; `workingDirectoryPath()` for the `Path` form),
  `agentSession()` (is it an `agent-*` sidechain?), `entries()`, `messages()`, and the fork
  partition `segments()`.
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

## Session metadata

Every `Session` carries a `Map<String, Serializable> metaData` — arbitrary SDK-managed metadata
associated with that session, kept in a `<sessionId>.meta` sidecar next to the transcript. Claude
Code itself is unaware of these files; only this SDK reads and writes them (the loader looks at
`*.jsonl` only, so `.meta` files are left undisturbed).

On load, the sidecar is deserialized into `metaData`; when there is no sidecar the field is an
empty (mutable, insertion-ordered) `LinkedHashMap`. There is no separate name/description — store
whatever you need as ordinary keys:

```java
Session s = client.getSession();

s.putMetaData("title", "Doc summarizer");
s.putMetaData("promptTemplate", "Summarize {{document}} for {{audience}}");
s.putMetaData("argSpec", new ArrayList<>(List.of("document", "audience")));

s.removeMetaData("title");
```

- **Always mutate through `putMetaData` / `removeMetaData`.** They update the in-memory map *and*
  immediately persist the `.meta` file, keeping the two in sync. (`writeMetaData()` is also exposed
  for the rare case where you mutate the map by other means and want to flush it.)
- Values must be `Serializable` and are stored with Java serialization, so reading them back needs
  the value classes on the classpath. Insertion order is preserved across a round trip.
- `Session` results are **point-in-time snapshots** (see above), so a `Session` is a *held*
  handle: mutate the same instance you intend to persist. Mutating one `getSession()` result and
  then writing a *different* one will not carry your change.
- Because `metaData` is a live mutable map, do not use a `Session` as a hash-map key or set element.

## Lightweight scanning (session browser)

To enumerate sessions cheaply — e.g. to render a picker — pass `dontLoadTranscripts = true` to any
`load` / `forWorkingDirectory` / `allUnder` variant. Each `Session` is then populated with its
identity, working directory, and metadata (`sessionId`, `file`, `agentSession`, `agentId`,
`workingDirectory`, `metaData`); `entries`, `messages`, `segments`, and `forkMarkers` are left
empty and no fork analysis runs (so `families()` is empty). This skips parsing every transcript
line in the directory (only as far as the first `cwd` is read, for the working directory).

```java
for (TranscriptDirectory dir : TranscriptDirectory.allUnder(true)) {   // metadata-only scan
    for (Session s : dir.sessions()) {
        String title = (String) s.metaData().getOrDefault("title", s.sessionId());
        // show `title` + s.workingDirectory() in a list; load the chosen session fully to work on it
    }
}

// Full load of the one the user picks — now entries/replay/archive are available:
Session chosen = TranscriptDirectory.forWorkingDirectory(workingDir).byId(pickedId).orElseThrow();
```

Sort the scan by recency for a most-recently-used list: `Session.lastUpdateTime()` returns the
later of `lastTranscriptUpdateTime()` and `lastMetaDataUpdateTime()` (the `.jsonl` and `.meta`
file mtimes); `metaFilePath()` exposes the sidecar path itself. These read file times only, so
they work on a lightweight `Session`.

`workingDirectory()` is also populated on a lightweight `Session` — the scan reads only as far as
the first transcript line that carries a `cwd`, rather than parsing the whole file — so a browser
can show the real directory each session ran in without a full load.

A lightweight `Session` is otherwise for browsing and metadata only: `replay()`, `archiveTo()`,
`isFork()`, `messages()` and the like depend on the parsed transcript, so load the session fully
(the default, `dontLoadTranscripts = false`) before using them. Metadata mutation (`putMetaData`
etc.) *does* work on a lightweight session, since it needs only `file` and `metaData`.

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

`Session.replayMessages()` (eager `List<Message>`) and `Session.replay()` (cold
`Flux<Message>`) emit the session's full history — root through leaf — in a form
compatible with live message handling. (`TranscriptDirectory.replayMessages(sessionId)` /
`replay(sessionId)` are id-addressed conveniences that delegate to the session; the
sibling-fork knowledge the markers need is precomputed at load time.)

- Conversation lines arrive as their parsed types (`UserMessage`, `AssistantMessage`, …).
- Every other line (`attachment`, `queue-operation`, `mode`, …) arrives as a
  `RawTranscriptMessage` carrying the raw type and JSON, so nothing is dropped and the
  consumer chooses what to surface.
- A `ForkMarker` is emitted at each fork boundary (parent id, child id, message index,
  sibling forks — enough to build branch navigation).
- A terminal `HistoryEnd` signals completion with the final message count.

```java
dir.byId(sessionId).orElseThrow().replay()
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

## Session archives

`SessionClone` makes a live sibling copy; `SessionArchive` instead packages a session as a
single portable file you can store, copy, and move around — and, unlike a clone, it defaults
to **keeping the original session id** on restore, so the restored copy *is* the same session.

`SessionArchive.create(sessionId, workingDir, targetArchive)` writes a ZIP (no external
dependency) containing **only the specified session** — never the siblings that share its
transcript folder:

```
manifest.json                  provenance (see SessionArchive.Manifest)
metadata.ser                   the session's .meta bytes (Java-serialized map; omitted if no .meta)
transcript/<sessionId>.jsonl   the one session's transcript (a fork already embeds its ancestors)
transcript/<sessionId>/...     externalized tool-result sidecar files, if any
workdir/...                    the entire working-directory tree
```

The archive's metadata is simply the session's [`.meta` sidecar](#session-metadata) — set it up
front through the session, then archive:

```java
Session s = client.getSession();
s.putMetaData("title", "Doc summarizer");
s.putMetaData("promptTemplate", "Summarize {{document}} for {{audience}}");

Path file = SessionArchive.create(s.sessionId(), Path.of("/work/original"),
        Path.of("/backups/summarizer.ccsession.zip"));
```

Restore inflates the working tree into a fresh directory, re-homes the transcript (rewriting every
path reference from the archived working directory to the new one), and materializes the
`<sessionId>.meta` sidecar so the restored session keeps its metadata:

```java
SessionArchive.RestoreResult r = SessionArchive.restore(file, Path.of("/work/restored"));
// r.sessionId() == the archived id (keep-id default).
// restore(file, dir, true) instead mints a new id — a fork-on-restore — and the
// .meta file is renamed to match the new id.

ClaudeSyncClient resumed = ClaudeClient.sync(
        CLIOptions.builder().resume(r.sessionId()).build())
    .workingDirectory(r.workingDirectory())
    .build();
```

- **Metadata** travels with the archive as the session's `.meta` map (arbitrary live Java objects —
  e.g. a prompt template plus an argument spec), turning a saved session into a primed, packaged
  "AI application" capsule. There is no separate name/description: use map keys.
- **`readManifest(file)`** returns provenance (id, original working dir, created-at, message count,
  whether metadata is present) **without** extracting the archive or deserializing the metadata —
  cheap enough to list a folder of backups. **`readMetaData(file)`** deserializes the metadata map
  on demand (so it needs the value classes on the classpath).
- The working tree is captured **in full** (no excludes), so an archive may be large and may
  include secrets (`.env`, `.git`, …) in one easily-shared file — handle accordingly. Metadata uses
  Java serialization, so treat an untrusted archive with the usual deserialization caution.

**Conveniences.** From a loaded `Session`, `session.archiveTo(file)` infers the working directory
(from the transcript's `cwd`) and projects root for you; from a live client,
`client.archiveSession(file)` archives the current session in one call. Both take the metadata from
the session's `.meta`; `archiveTo` additionally verifies the in-memory `metaData()` still matches
the on-disk `.meta` (and throws if you mutated the map without persisting it).

## Caveats

- **Local disk only.** These APIs read the transcripts of the machine the SDK runs on. If
  the CLI runs elsewhere (container, remote host), the history is on *that* machine.
- **Most-recent is best-effort.** With several sessions running concurrently in one
  directory, "most recently modified" may not be the one you mean — prefer
  `getSession(id)` when you have the id.
- **Flush timing.** The CLI writes the transcript asynchronously; immediately after a
  response there can be a brief window before the latest lines are on disk.
