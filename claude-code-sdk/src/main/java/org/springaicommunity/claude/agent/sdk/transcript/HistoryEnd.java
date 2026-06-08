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

package org.springaicommunity.claude.agent.sdk.transcript;

import org.springaicommunity.claude.agent.sdk.types.Message;

/**
 * A synthetic terminal message emitted at the end of {@link TranscriptDirectory#replay(String)},
 * signalling that the replayed history is complete. Transcripts have no per-turn result
 * message, so this is the consumer's end-of-stream signal. Its {@link #getType()} is
 * {@code "history_end"}.
 *
 * @param sessionId the session whose history was replayed
 * @param messageCount the number of history messages that preceded this marker
 */
public record HistoryEnd(String sessionId, int messageCount) implements Message {

	@Override
	public String getType() {
		return "history_end";
	}

	@Override
	public String toString() {
		return "[HistoryEnd " + sessionId + " messages=" + messageCount + "]";
	}
}
