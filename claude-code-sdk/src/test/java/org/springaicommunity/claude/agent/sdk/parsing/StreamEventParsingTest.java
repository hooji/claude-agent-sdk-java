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

package org.springaicommunity.claude.agent.sdk.parsing;

import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.types.Message;
import org.springaicommunity.claude.agent.sdk.types.StreamEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests parsing of {@code stream_event} partial-message lines (emitted by the CLI when
 * {@code --include-partial-messages} is enabled), which power token-level streaming.
 */
class StreamEventParsingTest {

	private final MessageParser parser = new MessageParser();

	@Test
	void parsesTextDeltaStreamEvent() throws Exception {
		String json = "{\"type\":\"stream_event\",\"session_id\":\"s1\",\"event\":{\"type\":\"content_block_delta\","
				+ "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}}";

		Message msg = parser.parseMessage(json);

		assertThat(msg).isInstanceOf(StreamEvent.class);
		StreamEvent event = (StreamEvent) msg;
		assertThat(event.getType()).isEqualTo("stream_event");
		assertThat(event.eventType()).isEqualTo("content_block_delta");
		assertThat(event.hasTextDelta()).isTrue();
		assertThat(event.textDelta()).contains("Hello");
		assertThat(event.thinkingDelta()).isEmpty();
	}

	@Test
	void nonTextStreamEventHasNoTextDelta() throws Exception {
		String json = "{\"type\":\"stream_event\",\"session_id\":\"s1\","
				+ "\"event\":{\"type\":\"message_stop\"}}";

		Message msg = parser.parseMessage(json);

		assertThat(msg).isInstanceOf(StreamEvent.class);
		StreamEvent event = (StreamEvent) msg;
		assertThat(event.eventType()).isEqualTo("message_stop");
		assertThat(event.hasTextDelta()).isFalse();
		assertThat(event.textDelta()).isEmpty();
	}

}
