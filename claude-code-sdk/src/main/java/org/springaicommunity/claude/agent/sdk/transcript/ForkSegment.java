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

/**
 * A contiguous run of a session's messages that were originally created in one session,
 * i.e. one cell of the fork partition.
 *
 * <p>A session's {@code segments} tile its uuid-bearing message list with no gaps or
 * overlaps: {@code segments[0].startIndex == 0}, and for each subsequent segment
 * {@code startIndex == previous.startIndex + previous.count}. The first segment is the
 * conversation root; the boundary at the start of every later segment is a fork point
 * (conceptually a fork occurs <em>between</em> two messages).
 *
 * @param originSessionId the session in which the messages of this segment were first
 * created (recovered via uuid provenance across the loaded directory)
 * @param startIndex index of this segment's first message within the session's message list
 * @param count number of messages in this segment
 */
public record ForkSegment(String originSessionId, int startIndex, int count) {

	/** @return the exclusive end index of this segment ({@code startIndex + count}). */
	public int endIndexExclusive() {
		return startIndex + count;
	}
}
