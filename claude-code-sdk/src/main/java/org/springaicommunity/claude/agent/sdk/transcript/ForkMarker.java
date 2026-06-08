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

import java.util.List;

import org.springaicommunity.claude.agent.sdk.types.Message;

/**
 * A synthetic message emitted during {@link TranscriptDirectory#replay(String)} at a fork
 * boundary, so a consumer (e.g. a UI) can mark where the conversation branched and offer
 * navigation to sibling branches. Its {@link #getType()} is {@code "fork_marker"}.
 *
 * @param parentSessionId the session the conversation was forked from
 * @param childSessionId the session created by the fork (whose messages follow this marker)
 * @param messageIndex the fork position as an index into the session's message list — the
 * same coordinate as {@link ForkSegment#startIndex()} (a fork occurs <em>between</em>
 * {@code messageIndex - 1} and {@code messageIndex}). Note this is the partition index and
 * may differ from the marker's position in the emitted replay stream, since entries that are
 * not conversation messages are not emitted.
 * @param siblingSessionIds other sessions forked from {@code parentSessionId} (for "jump to
 * another branch" navigation), possibly empty
 */
public record ForkMarker(String parentSessionId, String childSessionId, int messageIndex,
		List<String> siblingSessionIds) implements Message {

	@Override
	public String getType() {
		return "fork_marker";
	}

	@Override
	public String toString() {
		return "[ForkMarker " + shortId(parentSessionId) + " -> " + shortId(childSessionId) + " @" + messageIndex
				+ (siblingSessionIds.isEmpty() ? "" : " siblings=" + siblingSessionIds.size()) + "]";
	}

	private static String shortId(String id) {
		return id == null ? "?" : (id.length() > 8 ? id.substring(0, 8) : id);
	}
}
