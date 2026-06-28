# Token-Level Partial Message Streaming

By default the SDK yields whole messages: you see an `AssistantMessage` only once the CLI
has finished composing it. For interactive UIs that want text to appear as it is generated
(typewriter effect), the CLI can emit **partial message events**
(`--include-partial-messages`), and the async client surfaces them.

## Enabling

Partial events are opt-in at client build time:

```java
ClaudeAsyncClient client = ClaudeClient.async()
    .workingDirectory(".")
    .includePartialMessages(true)    // maps to --include-partial-messages
    .build();
```

Without this flag the CLI never emits partial events — the partial streams below stay
empty (they still complete normally at the end of the turn, they just yield nothing).

## Streaming text deltas

`TurnSpec.partialTextStream()` yields each incremental text delta for the turn:

```java
client.connect("Write a haiku about Java").partialTextStream()
    .doOnNext(System.out::print)     // prints token-by-token
    .doOnComplete(System.out::println)
    .subscribe();
```

It works on follow-up turns the same way (`client.query(...).partialTextStream()`), and
the message-level APIs (`text()`, `messages()`, `textStream()`) remain available on the
same client — partial events are additive, not a replacement.

## Raw events

When you need more than visible text — extended-thinking deltas, content-block
boundaries, message metadata — use `TurnSpec.partialEvents()`, which yields the
`StreamEvent` type:

| Accessor | Content |
|----------|---------|
| `eventType()` | The inner Anthropic event type: `message_start`, `content_block_delta`, `content_block_stop`, `message_delta`, … |
| `textDelta()` | `Optional<String>` — incremental text, present for `text_delta` events |
| `thinkingDelta()` | `Optional<String>` — incremental extended-thinking text, present for `thinking_delta` events |
| `rawEvent()` | The full underlying event as a `Map<String, Object>`, for anything else |

```java
client.query("Think hard, then answer").partialEvents()
    .doOnNext(ev -> {
        ev.thinkingDelta().ifPresent(ui::appendThinking);
        ev.textDelta().ifPresent(ui::appendAnswer);
    })
    .subscribe();
```

## Notes

- Partial streaming is an **async-client** feature (`ClaudeAsyncClient`). The sync
  client's iterator API is message-granularity.
- Each partial delta is also followed by the usual complete `AssistantMessage` at the end
  of the turn — if you render both, you'll see the text twice. Pick one granularity per
  surface.
- Parsing is tolerant: unknown event shapes flow through `rawEvent()` rather than failing
  the stream.
