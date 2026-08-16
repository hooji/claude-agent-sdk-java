# Cloud sessions (`ClaudeCloudSessions`)

`org.springaicommunity.claude.agent.sdk.sessions.ClaudeCloudSessions` lists
**Claude Code cloud sessions** (the sessions shown by `claude --teleport` and at
claude.ai/code) and reads their live status — including the `worker_status` idle
signal the teleport picker doesn't display. Useful for monitoring many parallel
cloud sessions and getting notified the moment one goes idle or needs an
approval.

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
import org.springaicommunity.claude.agent.sdk.sessions.ClaudeCloudSessions;
import org.springaicommunity.claude.agent.sdk.sessions.ClaudeCloudSessions.CloudSession;

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

## Fetching one session

```java
Optional<CloudSession> s = ClaudeCloudSessions.getCloudSession(token, "cse_...");
// or, using this machine's login:
Optional<CloudSession> s = ClaudeCloudSessions.getCloudSession("cse_...");
```

Issues `GET /v1/code/sessions/<id>`. Because the API is undocumented, a `404`/`405` on
the by-id path (ambiguous between "no such session" and "no such endpoint shape") falls
back to scanning the paged list, so an empty `Optional` really means the session isn't
visible to this token.

## Watching for turn completion

`watchForTurnEnd` invokes your callback **once**, when a session finishes its turn —
`workerStatus` observed as `"idle"` or `"requires_action"` — including immediately if
it is already in one of those states when the watch starts (the turn is already over):

```java
try (CloudSessionWatch watch = ClaudeCloudSessions.watchForTurnEnd(token, sessionId,
        Duration.ofSeconds(15),
        session -> System.out.println("done: " + session.title()),
        error -> log.warn("poll failed", error))) {
    // callback fires on a daemon polling thread; close() stops the watch early
}
```

The API has no push channel, so the watch polls `getCloudSession` under the covers. To
stay a good citizen against the undocumented API, intervals below **15 seconds**
(`CloudSessionWatch.MIN_POLL_INTERVAL`, also the default) are clamped up. Transient
polling errors go to the error handler (or the log) and polling continues; the watch
gives up after 8 consecutive failures or when the session disappears, and closes
itself after firing. For watches that may outlast the token's few-hour lifetime,
`refreshOAuthToken()` (below) keeps the credential usable.

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

### Token introspection & refresh

The stored credential can be inspected instead of failed on:

```java
boolean ok = ClaudeCloudSessions.isOAuthTokenValid();          // total: false on any problem
Optional<Duration> left = ClaudeCloudSessions.oauthTokenTimeRemaining(); // negative = expired

// the full stored credential (expired ones are returned, not thrown on):
var creds = ClaudeCloudSessions.getClaudeOAuthCredentials();
creds.isValid();          // present and not past expiresAt
creds.timeRemaining();    // Optional<Duration>, negative when expired
creds.scopes();           // e.g. [user:inference, user:sessions:claude_code]
creds.subscriptionType(); // e.g. "max"
```

(The refresh token deliberately stays unexposed.) And the refresh itself can be
driven from the SDK:

```java
String fresh = ClaudeCloudSessions.refreshOAuthToken();  // valid ≥5min, or refreshes
```

The CLI has no dedicated refresh command — it refreshes the stored credential
transparently whenever it makes an authenticated API request. `refreshOAuthToken()`
therefore triggers exactly that when (and only when) the stored token is expired or
near expiry: one minimal headless turn (`claude -p ok --max-turns 1 --model haiku`,
one tiny model call against your subscription), run with `ANTHROPIC_API_KEY` /
`ANTHROPIC_AUTH_TOKEN` / `CLAUDE_CODE_OAUTH_TOKEN` stripped from its environment so
the CLI must use — and thereby refresh — the stored login. The outcome is verified by
re-reading the credential; if it is still expired (e.g. the refresh token itself has
lapsed and `claude auth login` is needed), the method throws rather than pretending.

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

## Relabeling sessions (tags / title)

Sessions can be relabeled the way the Claude apps do it, via
`PUT /v1/code/sessions/<id>`:

```java
// Add / remove tags incrementally (the apps' color labels are ordinary tags
// with the "color:" prefix — see ClaudeCloudSessions.COLOR_TAG_PREFIX):
ClaudeCloudSessions.updateSessionTags(token, sessionId,
        List.of("my-project", "color:blue"),   // add
        List.of("color:red"));                 // remove

// Rename a session:
ClaudeCloudSessions.updateSessionTitle(token, sessionId, "Nightly refactor run");
```
