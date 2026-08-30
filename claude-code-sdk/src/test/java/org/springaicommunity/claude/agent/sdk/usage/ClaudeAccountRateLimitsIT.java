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

package org.springaicommunity.claude.agent.sdk.usage;

import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.test.ClaudeCliTestBase;
import org.springaicommunity.claude.agent.sdk.types.RateLimitSnapshot;
import org.springaicommunity.claude.agent.sdk.types.RateLimitWindow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ClaudeAccountRateLimits} against the real Claude CLI. Runs
 * one minimal Haiku probe turn (measured at a fraction of a cent). The
 * {@code rate_limit_event} only exists for claude.ai subscription accounts, so on
 * API-key-billed environments the fetch legitimately returns empty and the content
 * assertions are skipped.
 */
class ClaudeAccountRateLimitsIT extends ClaudeCliTestBase {

	@Test
	void fetchesAccountRateLimits() throws Exception {
		RateLimitSnapshot snapshot = ClaudeAccountRateLimits.fetch();

		Assumptions.assumeTrue(snapshot != null, "Skipping content assertions: no rate_limit_event was emitted "
				+ "(API-key billing has no unified limits, or the CLI predates the event)");

		assertThat(snapshot.status()).isIn("allowed", "allowed_warning", "rejected");
		assertThat(snapshot.age()).isLessThan(Duration.ofMinutes(5));
		assertThat(snapshot.event().rawValues()).isNotNull().containsKey("rate_limit_info");

		// Windows are reported by CLI 2.1.x+; when present they must be coherent
		for (RateLimitWindow window : snapshot.windows().values()) {
			if (window.utilization() != null) {
				assertThat(window.utilization()).isBetween(0.0, 1.0);
			}
			assertThat(window.resetsAtInstant()).isNotNull();
		}
		assertThat(snapshot.fiveHour()).as("five_hour window on a modern CLI").isNotNull();
	}

}
