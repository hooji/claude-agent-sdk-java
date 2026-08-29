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

package org.springaicommunity.claude.agent.sdk;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.parsing.ParsedMessage;
import org.springaicommunity.claude.agent.sdk.types.RateLimitEvent;
import org.springaicommunity.claude.agent.sdk.types.RateLimitInfo;
import org.springaicommunity.claude.agent.sdk.types.RateLimitWindow;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that both clients capture the newest {@code rate_limit_event} from inbound
 * messages and expose it via {@code latestRateLimit()}.
 */
class RateLimitCaptureTest {

	static ParsedMessage rateLimitEvent(double fiveHourUtilization) {
		RateLimitInfo info = new RateLimitInfo("allowed", 1788054000L, "five_hour", "rejected", "org_level_disabled",
				false, null, null, null, null, null, null, null,
				Map.of(RateLimitInfo.FIVE_HOUR, new RateLimitWindow(fiveHourUtilization, 1788054000L),
						RateLimitInfo.SEVEN_DAY, new RateLimitWindow(0.10, 1788231600L)));
		return ParsedMessage.RateLimitEventMessage
			.of(new RateLimitEvent("rate_limit_event", info, "uuid-1", "session-1", null));
	}

	static ParsedMessage regularMessage() {
		return ParsedMessage.RegularMessage.of(ResultMessage.builder().subtype("success").build());
	}

	@Test
	void syncClientCapturesLatestRateLimit() {
		DefaultClaudeSyncClient client = new DefaultClaudeSyncClient(".", null, null, null, null);

		assertThat(client.latestRateLimit()).isEmpty();

		client.captureRateLimit(rateLimitEvent(0.37));
		assertThat(client.latestRateLimit()).isPresent();
		assertThat(client.latestRateLimit().get().fiveHour().get().utilizationPercent()).isEqualTo(37.0);
		assertThat(client.latestRateLimit().get().isAllowed()).isTrue();

		// A newer event replaces the old one
		client.captureRateLimit(rateLimitEvent(0.42));
		assertThat(client.latestRateLimit().get().fiveHour().get().utilizationPercent()).isEqualTo(42.0);

		// Non-rate-limit messages must not clobber the captured value
		client.captureRateLimit(regularMessage());
		assertThat(client.latestRateLimit()).isPresent();
	}

	@Test
	void asyncClientCapturesLatestRateLimit() {
		DefaultClaudeAsyncClient client = new DefaultClaudeAsyncClient(".", null, null, null, null);

		assertThat(client.latestRateLimit()).isEmpty();

		client.captureRateLimit(rateLimitEvent(0.37));
		assertThat(client.latestRateLimit()).isPresent();
		assertThat(client.latestRateLimit().get().sevenDay().get().utilizationPercent()).isEqualTo(10.0);

		client.captureRateLimit(regularMessage());
		assertThat(client.latestRateLimit()).isPresent();
	}

}
