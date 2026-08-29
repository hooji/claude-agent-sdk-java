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
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One rate-limit window from a {@code rate_limit_event}'s {@code unifiedWindows} map —
 * e.g. the {@code five_hour} or {@code seven_day} window of a claude.ai subscription.
 *
 * @param utilization fraction of the window already used, {@code 0.0}–{@code 1.0} (the
 * CLI reports it rounded to two decimals, i.e. whole-percent steps); may be null when the
 * CLI omits it
 * @param resetsAt Unix epoch seconds when the window resets; may be null when the CLI
 * omits it
 */
public record RateLimitWindow(@JsonProperty("utilization") Double utilization,

		@JsonProperty("resetsAt") Long resetsAt) {

	/**
	 * The utilization as a percentage, {@code 0.0}–{@code 100.0}. Returns {@code 0.0}
	 * when the CLI did not report a utilization.
	 */
	public double utilizationPercent() {
		return utilization != null ? utilization * 100.0 : 0.0;
	}

	/**
	 * The reset time as an {@link Instant}, when reported.
	 */
	public Optional<Instant> resetsAtInstant() {
		return resetsAt != null && resetsAt > 0 ? Optional.of(Instant.ofEpochSecond(resetsAt)) : Optional.empty();
	}

	/**
	 * Time remaining until this window resets, floored at {@link Duration#ZERO}. Returns
	 * {@code ZERO} when the CLI did not report a reset time.
	 */
	public Duration timeUntilReset() {
		return resetsAtInstant().map(reset -> {
			Duration remaining = Duration.between(Instant.now(), reset);
			return remaining.isNegative() ? Duration.ZERO : remaining;
		}).orElse(Duration.ZERO);
	}

}
