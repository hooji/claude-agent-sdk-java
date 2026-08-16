/*
 * Copyright 2026 Spring AI Community
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

package org.springaicommunity.claude.agent.sdk.config;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link PermissionMode}, pinning the CLI wire values — including the
 * modes the CLI added later ({@code auto}, {@code plan}, {@code manual},
 * {@code dontAsk}).
 */
@DisplayName("PermissionMode")
class PermissionModeTest {

	@Test
	@DisplayName("maps every mode to its CLI value")
	void cliValues() {
		assertThat(PermissionMode.DEFAULT.getValue()).isEqualTo("default");
		assertThat(PermissionMode.MANUAL.getValue()).isEqualTo("manual");
		assertThat(PermissionMode.ACCEPT_EDITS.getValue()).isEqualTo("acceptEdits");
		assertThat(PermissionMode.AUTO.getValue()).isEqualTo("auto");
		assertThat(PermissionMode.PLAN.getValue()).isEqualTo("plan");
		assertThat(PermissionMode.DONT_ASK.getValue()).isEqualTo("dontAsk");
		assertThat(PermissionMode.BYPASS_PERMISSIONS.getValue()).isEqualTo("bypassPermissions");
		assertThat(PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS.getValue()).isEqualTo("dangerously-skip-permissions");
	}

	@Test
	@DisplayName("fromValue round-trips every mode")
	void fromValueRoundTrips() {
		for (PermissionMode mode : PermissionMode.values()) {
			assertThat(PermissionMode.fromValue(mode.getValue())).isSameAs(mode);
		}
	}

	@Test
	@DisplayName("fromValue rejects unknown values")
	void fromValueRejectsUnknown() {
		assertThatIllegalArgumentException().isThrownBy(() -> PermissionMode.fromValue("bogus"))
			.withMessageContaining("bogus");
	}

	@Test
	@DisplayName("covers the current CLI's --permission-mode choices")
	void coversCurrentCliChoices() {
		// The choice list advertised by `claude --help` as of CLI 2.1.233 (plus the
		// hidden legacy alias "default" the CLI still accepts).
		var cliChoices = Arrays.asList("acceptEdits", "auto", "bypassPermissions", "manual", "dontAsk", "plan");
		var enumValues = Arrays.stream(PermissionMode.values()).map(PermissionMode::getValue).toList();
		assertThat(enumValues).containsAll(cliChoices);
	}

}
