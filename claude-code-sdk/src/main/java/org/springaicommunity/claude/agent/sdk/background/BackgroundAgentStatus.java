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

package org.springaicommunity.claude.agent.sdk.background;

import java.nio.file.Path;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A point-in-time snapshot of one entry from {@code claude agents --json} — the supervisor's
 * view of a session. Both background agents and interactive sessions appear there;
 * {@link #isBackground()} distinguishes them.
 *
 * @param id the short id (the {@link #sessionId()}'s first segment) used by
 * {@code claude logs}/{@code stop}/{@code attach}
 * @param sessionId the full session id (the transcript filename), or {@code null} for entries
 * that don't carry one
 * @param cwd the working directory the session runs in
 * @param kind {@code "background"} or {@code "interactive"}
 * @param startedAt when the session started, or {@code null}
 * @param name a short label (for a background agent, derived from its prompt), or {@code null}
 * @param state the lifecycle {@link BackgroundAgentState} (background agents only; interactive
 * entries report {@link BackgroundAgentState#UNKNOWN})
 * @param activity the finer-grained {@code status} sub-field (e.g. {@code idle}/{@code running}),
 * or {@code null}
 * @param pid the OS process id, or {@code null}
 */
public record BackgroundAgentStatus(String id, String sessionId, Path cwd, String kind, Instant startedAt, String name,
		BackgroundAgentState state, String activity, Integer pid) {

	/** @return whether this entry is a background agent (vs an interactive session). */
	public boolean isBackground() {
		return "background".equals(kind);
	}

	/** Parses one {@code claude agents --json} array element. */
	static BackgroundAgentStatus from(JsonNode n) {
		String cwd = text(n, "cwd");
		return new BackgroundAgentStatus(text(n, "id"), text(n, "sessionId"), cwd == null ? null : Path.of(cwd),
				text(n, "kind"), n.hasNonNull("startedAt") ? Instant.ofEpochMilli(n.get("startedAt").asLong()) : null,
				text(n, "name"), BackgroundAgentState.fromWire(text(n, "state")), text(n, "status"),
				n.hasNonNull("pid") ? n.get("pid").asInt() : null);
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}

}
