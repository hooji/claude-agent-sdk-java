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
 * An independent conversation and all sessions forked from it — i.e. one connected
 * component of the fork relationship, materialized as a tree.
 *
 * @param rootSessionId the originating session id shared by every session in this family
 * @param tree the fork tree rooted at the original conversation
 * @param members every session in this family (flattened, for convenience)
 */
public record ConversationFamily(String rootSessionId, ForkNode tree, List<Session> members) {
}
