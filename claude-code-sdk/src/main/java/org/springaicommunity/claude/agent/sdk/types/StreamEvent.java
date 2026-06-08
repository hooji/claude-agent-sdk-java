/*
 * Copyright 2025 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.claude.agent.sdk.types;

import java.util.Map;
import java.util.Optional;

/**
 * A partial streaming event emitted by the Claude CLI while a response is being
 * generated, enabling token-level streaming.
 *
 * <p>These events are only produced when the session is configured with
 * {@code includePartialMessages(true)} (CLI flag {@code --include-partial-messages}).
 * Each event wraps a raw Anthropic streaming event (for example
 * {@code content_block_delta}); the common case is an incremental text delta available
 * via {@link #textDelta()}. The full underlying event is available via {@link #rawEvent()}
 * for advanced consumers (block start/stop, message_delta, etc.).
 *
 * @param eventType the inner Anthropic event type (e.g. {@code content_block_delta},
 * {@code message_start}, {@code message_stop}), or {@code null} if absent
 * @param text the incremental text for a {@code text_delta}, otherwise {@code null}
 * @param thinking the incremental extended-thinking text for a {@code thinking_delta},
 * otherwise {@code null}
 * @param rawEvent the full raw inner event as a map, for advanced use
 */
public record StreamEvent(String eventType, String text, String thinking, Map<String, Object> rawEvent)
		implements Message {

	@Override
	public String getType() {
		return "stream_event";
	}

	/**
	 * The incremental text delta carried by this event, if any. This is the primary
	 * accessor for token-level streaming of the assistant's visible response.
	 * @return the text delta, or empty if this event is not a text delta
	 */
	public Optional<String> textDelta() {
		return Optional.ofNullable(text);
	}

	/**
	 * The incremental extended-thinking delta carried by this event, if any.
	 * @return the thinking delta, or empty if this event is not a thinking delta
	 */
	public Optional<String> thinkingDelta() {
		return Optional.ofNullable(thinking);
	}

	/**
	 * @return true if this event carries a non-empty text delta
	 */
	public boolean hasTextDelta() {
		return text != null && !text.isEmpty();
	}

	@Override
	public String toString() {
		if (hasTextDelta()) {
			return "[StreamEvent " + eventType + " text=\"" + text + "\"]";
		}
		return "[StreamEvent " + eventType + "]";
	}
}
