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

/**
 * A node in a conversation's fork tree: one session plus the sessions forked from it.
 *
 * @param session the session at this node
 * @param forkPointInParent the index in the parent's message list at which this session
 * diverged, or -1 for the root
 * @param children sessions forked directly from {@code session}
 */
public record ForkNode(Session session, int forkPointInParent, List<ForkNode> children) {

	/** @return this node's session id. */
	public String sessionId() {
		return session.sessionId();
	}
}
