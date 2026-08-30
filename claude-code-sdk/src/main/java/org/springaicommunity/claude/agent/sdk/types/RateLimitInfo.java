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

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rate limit information from a {@code rate_limit_event} server-sent event, mirroring the
 * official Agent SDK's {@code SDKRateLimitInfo} type. Reported for claude.ai subscription
 * (Pro/Max) accounts; API-key billing has no unified limits and never produces this
 * event.
 *
 * <p>
 * The top-level {@code status} / {@code resetsAt} / {@code rateLimitType} /
 * {@code utilization} fields describe the <em>representative</em> window — the one the
 * CLI considers most relevant right now. Newer CLIs additionally report every window in
 * {@link #unifiedWindows()} ({@code five_hour}, {@code seven_day}, and model-scoped
 * variants), each with its own utilization and reset time; prefer {@link #fiveHour()} /
 * {@link #sevenDay()} when present.
 * </p>
 *
 * @param status {@code allowed}, {@code allowed_warning}, or {@code rejected}
 * @param resetsAt Unix epoch seconds when the representative window resets ({@code 0}
 * when absent)
 * @param rateLimitType representative window kind: {@code five_hour}, {@code seven_day},
 * {@code seven_day_opus}, {@code seven_day_sonnet}, {@code seven_day_overage_included},
 * or {@code overage}
 * @param overageStatus whether extra-usage (overage) is {@code allowed},
 * {@code allowed_warning}, or {@code rejected}
 * @param overageDisabledReason why overage is unavailable (e.g.
 * {@code org_level_disabled}, {@code overage_not_provisioned}, {@code out_of_credits})
 * @param isUsingOverage whether the current request is being served from overage
 * @param utilization fraction of the representative window already used,
 * {@code 0.0}–{@code 1.0}; null when the CLI omits it (older CLIs report utilization only
 * inside {@code unifiedWindows})
 * @param overageResetsAt Unix epoch seconds when the overage window resets; null when
 * absent
 * @param overageInUse newer alias of {@code isUsingOverage}; null when absent
 * @param surpassedThreshold warning threshold that was crossed, when the CLI reports one
 * @param errorCode machine-readable error, e.g. {@code credits_required}
 * @param canUserPurchaseCredits whether the user could buy extra-usage credits
 * @param hasChargeableSavedPaymentMethod whether a chargeable payment method is on file
 * @param unifiedWindows per-window utilization and reset time, keyed by window kind
 * ({@code five_hour}, {@code seven_day}, ...); null on older CLIs
 */
public record RateLimitInfo(@JsonProperty("status") String status,

		@JsonProperty("resetsAt") long resetsAt,

		@JsonProperty("rateLimitType") String rateLimitType,

		@JsonProperty("overageStatus") String overageStatus,

		@JsonProperty("overageDisabledReason") String overageDisabledReason,

		@JsonProperty("isUsingOverage") boolean isUsingOverage,

		@JsonProperty("utilization") Double utilization,

		@JsonProperty("overageResetsAt") Long overageResetsAt,

		@JsonProperty("overageInUse") Boolean overageInUse,

		@JsonProperty("surpassedThreshold") Double surpassedThreshold,

		@JsonProperty("errorCode") String errorCode,

		@JsonProperty("canUserPurchaseCredits") Boolean canUserPurchaseCredits,

		@JsonProperty("hasChargeableSavedPaymentMethod") Boolean hasChargeableSavedPaymentMethod,

		@JsonProperty("unifiedWindows") Map<String, RateLimitWindow> unifiedWindows) {

	/** Window key of the rolling 5-hour limit in {@link #unifiedWindows()}. */
	public static final String FIVE_HOUR = "five_hour";

	/** Window key of the rolling 7-day limit in {@link #unifiedWindows()}. */
	public static final String SEVEN_DAY = "seven_day";

	/**
	 * Whether the request was allowed through the rate limit.
	 */
	public boolean isAllowed() {
		return "allowed".equals(status);
	}

	/**
	 * All reported windows, keyed by window kind — an empty map on older CLIs that don't
	 * send {@code unifiedWindows} (never null, unlike the raw accessor).
	 */
	public Map<String, RateLimitWindow> windows() {
		return unifiedWindows != null ? unifiedWindows : Map.of();
	}

	/**
	 * The window of the given kind (e.g. {@link #FIVE_HOUR}), or null when not reported.
	 */
	public RateLimitWindow window(String kind) {
		return windows().get(kind);
	}

	/**
	 * The rolling 5-hour window, or null when not reported.
	 */
	public RateLimitWindow fiveHour() {
		return window(FIVE_HOUR);
	}

	/**
	 * The rolling 7-day window, or null when not reported.
	 */
	public RateLimitWindow sevenDay() {
		return window(SEVEN_DAY);
	}

	/**
	 * The representative window's reset time as an {@link Instant}, or null when not
	 * reported.
	 */
	public Instant resetsAtInstant() {
		return resetsAt > 0 ? Instant.ofEpochSecond(resetsAt) : null;
	}

}
