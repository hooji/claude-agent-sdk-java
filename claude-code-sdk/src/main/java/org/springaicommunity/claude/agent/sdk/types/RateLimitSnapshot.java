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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * A {@link RateLimitEvent} paired with the moment it was received. The CLI emits rate
 * limit events only when the reported values change (see {@link RateLimitEvent}), so a
 * snapshot held by a long-lived client can age; {@link #age()} says by how much.
 *
 * @param event the received event
 * @param receivedAt when this SDK observed the event
 */
public record RateLimitSnapshot(RateLimitEvent event, Instant receivedAt) {

	public RateLimitSnapshot {
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		if (receivedAt == null) {
			throw new IllegalArgumentException("receivedAt must not be null");
		}
	}

	/**
	 * Wraps an event received just now.
	 */
	public static RateLimitSnapshot of(RateLimitEvent event) {
		return new RateLimitSnapshot(event, Instant.now());
	}

	/**
	 * The rate limit details carried by the event.
	 */
	public RateLimitInfo info() {
		return event.rateLimitInfo();
	}

	/**
	 * The representative window's status: {@code allowed}, {@code allowed_warning}, or
	 * {@code rejected}; null when the event carried no info.
	 */
	public String status() {
		return info() != null ? info().status() : null;
	}

	/**
	 * Whether requests are currently allowed through the rate limit.
	 */
	public boolean isAllowed() {
		return event.isAllowed();
	}

	/**
	 * All reported windows keyed by kind ({@code five_hour}, {@code seven_day}, ...);
	 * empty on older CLIs.
	 */
	public Map<String, RateLimitWindow> windows() {
		return info() != null ? info().windows() : Map.of();
	}

	/**
	 * The rolling 5-hour window, or null when not reported.
	 */
	public RateLimitWindow fiveHour() {
		return windows().get(RateLimitInfo.FIVE_HOUR);
	}

	/**
	 * The rolling 7-day window, or null when not reported.
	 */
	public RateLimitWindow sevenDay() {
		return windows().get(RateLimitInfo.SEVEN_DAY);
	}

	/**
	 * How long ago this snapshot was received.
	 */
	public Duration age() {
		return Duration.between(receivedAt, Instant.now());
	}

}
