# claude-cloud-sessions

> **Moved into the main SDK:** this class now ships as part of `claude-code-sdk`
> (and the `claude-code-sdk-all` fat jar) as
> `org.springaicommunity.claude.agent.sdk.sessions.ClaudeCloudSessions`.
> Use that copy; this standalone module is kept only until the integrated
> release is confirmed, then it will be removed. Everything below still
> applies — only the package name differs.

A single-file library for listing **Claude Code cloud sessions**
(the sessions shown by `claude --teleport` and at claude.ai/code) and reading
their live status — including the `worker_status` idle signal the teleport
picker doesn't display. Useful for monitoring many parallel cloud sessions and
getting notified the moment one goes idle or needs an approval.

It calls the same endpoint the Claude Code CLI itself uses:

```
GET https://api.anthropic.com/v1/code/sessions
Authorization: Bearer <claude.ai OAuth access token>
anthropic-version: 2023-06-01
```

> **Heads-up:** this endpoint is *undocumented* (the CLI version-tags it
> `v1alpha2` internally). It can change without notice. The library parses
> defensively and preserves every raw value in a string map so new fields are
> never lost.

## Requirements

- Java 17+ (uses records and `java.net.http`)
- Jackson databind (version managed by the parent pom)

## Usage

```java
import org.springaicommunity.claude.cloudsessions.ClaudeCloudSessions;
import org.springaicommunity.claude.cloudsessions.ClaudeCloudSessions.CloudSession;

// Uses this machine's Claude Code login (Keychain on macOS,
// ~/.claude/.credentials.json on Linux) and fetches ALL pages:
List<CloudSession> sessions = ClaudeCloudSessions.listCloudSessions();

// Explicit token; first page only (~20 most recently active sessions,
// same set the `claude --teleport` picker shows):
List<CloudSession> recent = ClaudeCloudSessions.listCloudSessions(token, true);

for (CloudSession s : sessions) {
    System.out.printf("%-28s %-8s %-14s %-12s %s%n",
        s.id(), s.status(), s.workerStatus(), s.statusBucket(), s.title());
}
```

Monitoring loop condition ideas (see field docs below):

- **finished a turn** → `workerStatus` transitions to `"idle"`
  (convenience: `s.isIdle()`)
- **blocked on you** → `workerStatus` becomes `"requires_action"`
  (tool permission / plan approval; convenience: `s.requiresAction()`)
- richer detail → `externalMetadata().postTurnSummary()` carries
  `statusCategory` (`review_ready`, `need_input`, ...), a one-line
  `statusDetail`, and `needsAction`

## Getting a token

```java
String token = ClaudeCloudSessions.getClaudeOAuthToken();          // auto: macOS or Linux
String token = ClaudeCloudSessions.getClaudeOAuthTokenLinux(       // explicit config dir
        Path.of("/home/me/.claude-account2"));                     // multi-account setups
```

- **macOS**: runs `security find-generic-password -a $USER -s "Claude Code-credentials" -w`
  and extracts `claudeAiOauth.accessToken`.
- **Linux**: reads `<configDir>/.credentials.json` (default `~/.claude`).

Two accounts on one machine = two config dirs (launch each Claude Code with its
own `CLAUDE_CONFIG_DIR`), then call the Linux variant once per directory.

### Token rules (learned the hard way)

- Only the **short-lived interactive login token** works. It carries the
  `user:sessions:claude_code` scope. Long-lived tokens from
  `claude setup-token` / `CLAUDE_CODE_OAUTH_TOKEN` are **inference-only by
  design** and get an authentication error on this endpoint.
- Access tokens expire after a few hours. The helper throws a descriptive
  error if the stored token is already expired — running any `claude` command
  refreshes the stored credential. For a long-running poller, simply re-read
  the token (cheap) on every cycle, or at least on any 401.

## API behavior notes

- **Ordering**: the server returns sessions sorted by `last_event_at`
  descending (verified empirically and by the pagination cursor, which encodes
  the last row's `last_event_at`). Actively running sessions keep bubbling to
  the top because they continuously append events.
- **Pagination**: each page carries a `next_cursor`; the next page is requested
  with `?cursor=<next_cursor>` (the CLI's own convention). Page size observed:
  20. `listCloudSessions(token, false)` follows the chain to the end, de-dupes
  by session id, and fails loudly if the cursor stops advancing.
- **Archived sessions** are interleaved (ordering is purely by activity time) —
  filter on `status()` if you only want `"active"`.
- `user_message_count` arrives as a **string** on the wire; the record exposes
  it as a `long`.

## The `CloudSession` record

Typed fields (all observed wire fields): `id`, `title`, `status`
(`active`/`archived`), `statusBucket` (`review_ready`/`blocked`/`completed`/...),
`workerStatus` (`idle`/`requires_action`/else-working), `connectionStatus`,
`environmentId`, `environmentKind` (`anthropic_cloud`/`bridge`), `createdAt`,
`lastEventAt` (both `java.time.Instant`), `unread`, `userMessageCount`, `tags`,
`participants`, `relations`, `config` (model, effort level, origin, sources,
outcomes incl. git branches), `externalMetadata` (post-turn summary, current
branches, last served model).

Plus **`allValues()`** — the entire raw session JSON flattened to a
`Map<String, String>` with dotted paths, so nothing is ever dropped:

```
config.sources.0.url            -> https://github.com/hooji/WebDSLForJava
external_metadata.post_turn_summary.status_category -> review_ready
tags.0                          -> remote-control-sdk
config.outcomes.0.git_info.ref  -> null        (JSON null renders as "null")
```

`parsePage(String json)` is also public, so you can parse captured responses
(fixtures, logs) without any network access; it exposes the page's
`nextCursor` and `resumeToken` too.
