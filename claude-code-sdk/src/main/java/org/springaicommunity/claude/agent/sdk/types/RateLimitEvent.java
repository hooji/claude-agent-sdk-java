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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Server-sent event carrying rate limit status ({@code type: "rate_limit_event"} in the
 * CLI's stream-json output; {@code SDKRateLimitEvent} in the official Agent SDK types).
 * The CLI derives it from the {@code anthropic-ratelimit-unified-*} headers of API
 * responses, so one is emitted on the first inference response of a session and again
 * whenever the reported values change — not as a continuous per-turn snapshot.
 *
 * @param type always {@code rate_limit_event}
 * @param rateLimitInfo the rate limit status, quota windows, and reset timing
 * @param uuid unique id of this event
 * @param sessionId id of the CLI session that observed it
 * @param rawValues the complete event JSON as a nested map, preserving fields this SDK
 * version doesn't model yet; null when the event was constructed by hand rather than
 * parsed off the wire
 */
public record RateLimitEvent(@JsonProperty("type") String type,

		@JsonProperty("rate_limit_info") RateLimitInfo rateLimitInfo,

		@JsonProperty("uuid") String uuid,

		@JsonProperty("session_id") String sessionId,

		@JsonProperty("rawValues") Map<String, Object> rawValues) {

	/**
	 * Whether the request was allowed through the rate limit.
	 */
	public boolean isAllowed() {
		return rateLimitInfo != null && rateLimitInfo.isAllowed();
	}

	/**
	 * Returns a copy of this event with {@link #rawValues()} set.
	 */
	public RateLimitEvent withRawValues(Map<String, Object> rawValues) {
		return new RateLimitEvent(type, rateLimitInfo, uuid, sessionId, rawValues);
	}

}
