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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the rate limit convenience types: {@link RateLimitWindow},
 * {@link RateLimitInfo} window accessors, and {@link RateLimitSnapshot}.
 */
class RateLimitTypesTest {

	private static RateLimitInfo infoWithWindows() {
		return new RateLimitInfo("allowed", 1788054000L, "five_hour", "rejected", "org_level_disabled", false, null,
				null, null, null, null, null, null,
				Map.of(RateLimitInfo.FIVE_HOUR, new RateLimitWindow(0.37, 1788054000L), RateLimitInfo.SEVEN_DAY,
						new RateLimitWindow(0.10, 1788231600L)));
	}

	private static RateLimitInfo infoWithoutWindows() {
		return new RateLimitInfo("allowed", 0L, "five_hour", null, null, false, null, null, null, null, null, null,
				null, null);
	}

	@Test
	void windowExposesPercentAndInstant() {
		RateLimitWindow window = new RateLimitWindow(0.37, 1788054000L);

		assertThat(window.utilizationPercent()).isEqualTo(37.0, within(1e-9));
		assertThat(window.resetsAtInstant()).contains(Instant.ofEpochSecond(1788054000L));
	}

	@Test
	void windowToleratesMissingValues() {
		RateLimitWindow window = new RateLimitWindow(null, null);

		assertThat(window.utilizationPercent()).isEqualTo(0.0);
		assertThat(window.resetsAtInstant()).isEmpty();
		assertThat(window.timeUntilReset()).isEqualTo(Duration.ZERO);
	}

	@Test
	void windowTimeUntilResetFloorsAtZeroForPastResets() {
		RateLimitWindow past = new RateLimitWindow(0.5, Instant.now().minusSeconds(3600).getEpochSecond());
		assertThat(past.timeUntilReset()).isEqualTo(Duration.ZERO);

		RateLimitWindow future = new RateLimitWindow(0.5, Instant.now().plusSeconds(3600).getEpochSecond());
		assertThat(future.timeUntilReset()).isPositive();
	}

	@Test
	void infoExposesTypedWindows() {
		RateLimitInfo info = infoWithWindows();

		assertThat(info.fiveHour()).isPresent();
		assertThat(info.fiveHour().get().utilizationPercent()).isEqualTo(37.0, within(1e-9));
		assertThat(info.sevenDay()).isPresent();
		assertThat(info.sevenDay().get().resetsAtInstant()).contains(Instant.ofEpochSecond(1788231600L));
		assertThat(info.window("seven_day_sonnet")).isEmpty();
		assertThat(info.resetsAtInstant()).contains(Instant.ofEpochSecond(1788054000L));
	}

	@Test
	void infoWithoutUnifiedWindowsIsNullSafe() {
		RateLimitInfo info = infoWithoutWindows();

		assertThat(info.windows()).isEmpty();
		assertThat(info.fiveHour()).isEmpty();
		assertThat(info.sevenDay()).isEmpty();
		assertThat(info.resetsAtInstant()).isEmpty();
	}

	@Test
	void snapshotDelegatesToEventAndTracksAge() {
		RateLimitEvent event = new RateLimitEvent("rate_limit_event", infoWithWindows(), "uuid-1", "session-1", null);
		Instant receivedAt = Instant.now().minusSeconds(120);
		RateLimitSnapshot snapshot = new RateLimitSnapshot(event, receivedAt);

		assertThat(snapshot.info()).isSameAs(event.rateLimitInfo());
		assertThat(snapshot.status()).isEqualTo("allowed");
		assertThat(snapshot.isAllowed()).isTrue();
		assertThat(snapshot.fiveHour()).isPresent();
		assertThat(snapshot.sevenDay()).isPresent();
		assertThat(snapshot.age()).isGreaterThanOrEqualTo(Duration.ofSeconds(120));

		RateLimitSnapshot fresh = RateLimitSnapshot.of(event);
		assertThat(fresh.age()).isLessThan(Duration.ofMinutes(1));
	}

	@Test
	void snapshotToleratesEventWithoutInfo() {
		RateLimitEvent event = new RateLimitEvent("rate_limit_event", null, "uuid-1", "session-1", null);
		RateLimitSnapshot snapshot = RateLimitSnapshot.of(event);

		assertThat(snapshot.status()).isNull();
		assertThat(snapshot.isAllowed()).isFalse();
		assertThat(snapshot.windows()).isEmpty();
		assertThat(snapshot.fiveHour()).isEmpty();
	}

	@Test
	void snapshotRejectsNulls() {
		RateLimitEvent event = new RateLimitEvent("rate_limit_event", null, "u", "s", null);

		assertThatThrownBy(() -> new RateLimitSnapshot(null, Instant.now()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitSnapshot(event, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void eventWithRawValuesKeepsIdentityFields() {
		RateLimitEvent event = new RateLimitEvent("rate_limit_event", infoWithWindows(), "uuid-1", "session-1", null);
		RateLimitEvent withRaw = event.withRawValues(Map.of("type", "rate_limit_event"));

		assertThat(withRaw.rawValues()).containsEntry("type", "rate_limit_event");
		assertThat(withRaw.uuid()).isEqualTo("uuid-1");
		assertThat(withRaw.rateLimitInfo()).isSameAs(event.rateLimitInfo());
	}

}
