# Account Rate Limits

`ClaudeAccountRateLimits` reads the account's current claude.ai subscription rate limits —
the rolling **5-hour** and **7-day** usage windows with their utilization percentages and
reset times — through a **supported** mechanism: the Claude CLI's stream-json
`rate_limit_event`. No undocumented HTTP endpoint is involved.

```java
import org.springaicommunity.claude.agent.sdk.usage.ClaudeAccountRateLimits;

RateLimitSnapshot snapshot = ClaudeAccountRateLimits.fetch();
if (snapshot != null) {
    snapshot.fiveHour().ifPresent(w -> System.out.printf("5h window: %.0f%% used, resets %s%n",
            w.utilizationPercent(), w.resetsAtInstant().orElse(null)));
    snapshot.sevenDay().ifPresent(w -> System.out.printf("7d window: %.0f%% used, resets %s%n",
            w.utilizationPercent(), w.resetsAtInstant().orElse(null)));
}
```

```
5h window: 37% used, resets 2026-08-30T07:00:00Z
7d window: 10% used, resets 2026-09-01T08:20:00Z
```

## Why this path, and not `/api/oauth/usage`

There are three ways to get at this data; only one is both supported and reachable from a
library:

| Path | Status | Verdict for the SDK |
|---|---|---|
| `GET /api/oauth/usage` with the CLI's OAuth token | **Undocumented.** Works today, known to 429 aggressively, shape changes without notice, and consumer-OAuth terms make third-party reuse a gray area | Avoided entirely |
| Statusline stdin JSON (`rate_limits` field) | Documented, but only delivered to the `statusLine.command` subprocess of an **interactive TUI** session — there is no way for a library to invoke it | Not reachable from the SDK |
| stream-json **`rate_limit_event`** | Part of the official Agent SDK protocol (`SDKRateLimitEvent` / `SDKRateLimitInfo` in the TypeScript SDK typings). The CLI derives it from the `anthropic-ratelimit-unified-*` headers of Anthropic API responses | **This is the one** |

## Event semantics (verified against CLI 2.1.251)

* The event is emitted on the **first API inference response** of a session, and after
  that **only when the reported values change**. It is not a per-turn snapshot.
* Because it is derived from API response headers, a session that performs **no
  inference** (e.g. a local slash command) never produces one.
* It exists only for **claude.ai subscription (Pro/Max) authentication**. API-key billing
  has no unified windows and never produces the event.
* Utilization is a `0.0`–`1.0` fraction, reported in whole-percent steps
  (`0.37` = 37% used). `resetsAt` is Unix epoch seconds.
* Current CLIs report every window in a `unifiedWindows` map (`five_hour`, `seven_day`,
  and model-scoped variants such as `seven_day_sonnet` when applicable) alongside the
  older top-level representative-window fields. The raw event payload is preserved on
  `RateLimitEvent.rawValues()` since the wire shape is still evolving.

Captured wire example:

```json
{
  "type": "rate_limit_event",
  "rate_limit_info": {
    "status": "allowed",
    "resetsAt": 1788054000,
    "rateLimitType": "five_hour",
    "overageStatus": "rejected",
    "overageDisabledReason": "org_level_disabled",
    "isUsingOverage": false,
    "unifiedWindows": {
      "five_hour":  { "utilization": 0.37, "resetsAt": 1788054000 },
      "seven_day":  { "utilization": 0.10, "resetsAt": 1788231600 }
    }
  },
  "uuid": "…",
  "session_id": "…"
}
```

## Two ways to get a snapshot

### 1. Standalone: `ClaudeAccountRateLimits.fetch()`

Works with no pre-existing session. It launches a minimal disposable probe session,
captures the event from the first API response, and tears the session down:

* **Haiku** by default (any model's response carries the same account-wide headers, so
  the cheapest one wins), single turn (`--max-turns 1`)
* all built-in tools disabled (`--tools ""`), hooks disabled, MCP servers not loaded
* a one-line replacement system prompt (this is the main cost saver — no default system
  prompt, no tool schemas)
* run in a throwaway temp directory so project context (CLAUDE.md etc.) stays out of the
  token bill, default permission mode so it also works when running as root

Measured on CLI 2.1.251: **≈ $0.0016 and 3–4 seconds** per fetch. The probe consumes a
correspondingly tiny amount of the very quota it measures — fine for on-demand checks and
polling on the order of minutes, wasteful as a per-second ticker.

```java
// Defaults: haiku, 2-minute timeout, CLI-managed login
RateLimitSnapshot limits = ClaudeAccountRateLimits.fetch();

// Custom: a specific model, tighter timeout, headless token auth
RateLimitSnapshot custom = ClaudeAccountRateLimits.fetch(
        ClaudeAccountRateLimits.FetchOptions.builder()
            .model("haiku")
            .timeout(Duration.ofSeconds(60))
            .oauthToken(System.getenv("CLAUDE_CODE_OAUTH_TOKEN")) // optional
            .build());
```

`fetch()` returns null when the probe session completes normally but the CLI reports no
rate limit event — that is what API-key billing looks like. A probe that fails outright
(CLI missing, unauthenticated, startup failure) throws `ClaudeSDKException`, with the
CLI's stderr tail in the message when the process died early.

### 2. On a connected session: `client.latestRateLimit()`

Every `ClaudeSyncClient` and `ClaudeAsyncClient` session now captures these events as a
side effect of normal conversation — if you already have a client doing work, the
snapshot is **free** (no probe, no extra tokens):

```java
try (ClaudeSyncClient client = ClaudeClient.sync().build()) {
    client.connect("Refactor this method…");
    client.messages().forEach(System.out::println);

    client.latestRateLimit().ifPresent(snapshot ->
        System.out.printf("after this turn: 5h window at %.0f%%%n",
                snapshot.fiveHour().map(RateLimitWindow::utilizationPercent).orElse(0.0)));
}
```

`latestRateLimit()` is empty until the session's first inference response arrives. Since
the CLI only re-emits on change, the held snapshot can age during a long-idle session —
`RateLimitSnapshot.age()` tells you by how much.

The async client additionally exposes the raw event stream:

```java
client.rateLimitEvents()
    .filter(e -> !e.isAllowed())
    .subscribe(e -> alerting.notify("Claude rate limited until "
            + e.rateLimitInfo().resetsAtInstant().orElse(null)));
```

## Types

| Type | Purpose |
|---|---|
| `RateLimitSnapshot` | A received event plus `receivedAt` / `age()`; convenience accessors `fiveHour()`, `sevenDay()`, `windows()`, `status()`, `isAllowed()` |
| `RateLimitEvent` | The wire event: `rateLimitInfo`, session id, event uuid, and `rawValues()` (the complete payload as a map, future-proof) |
| `RateLimitInfo` | Mirrors the official `SDKRateLimitInfo`: `status` (`allowed` / `allowed_warning` / `rejected`), representative window fields, overage fields (`overageStatus`, `overageDisabledReason`, `isUsingOverage`, …), and `unifiedWindows` |
| `RateLimitWindow` | One window: `utilization` (0–1), `utilizationPercent()`, `resetsAt` epoch seconds, `resetsAtInstant()`, `timeUntilReset()` |

## Requirements

* Claude CLI recent enough to emit `rate_limit_event` with `unifiedWindows` and to accept
  `--tools` (2.1.x, early 2026 onward; verified on 2.1.251)
* claude.ai subscription authentication (Pro/Max) — for API-key billing the event does
  not exist and `fetch()` returns null
