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

package org.springaicommunity.claude.agent.sdk.config;

import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.config.ClaudeAuth.AuthStatus;
import org.springaicommunity.claude.agent.sdk.test.ClaudeCliTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ClaudeAuth} against the real Claude CLI. The auth state of
 * the machine running the tests is unknown (logged in interactively, token-injected, or
 * logged out), so assertions are structural: the command must succeed and parse
 * coherently in every state.
 */
class ClaudeAuthIT extends ClaudeCliTestBase {

	@Override
	protected boolean requiresApi() {
		// `claude auth status` is a local command; no model access involved
		return false;
	}

	@Test
	void readsStatusAndParsesCleanly() throws Exception {
		AuthStatus status = ClaudeAuth.status();

		assertThat(status.allValues()).isNotEmpty().containsKey("loggedIn");
		assertThat(status.allValues().get("loggedIn")).isEqualTo(String.valueOf(status.loggedIn()));
		if (status.email() != null) {
			assertThat(status.allValues()).containsEntry("email", status.email());
			assertThat(status.hasIdentity()).isTrue();
		}
		else {
			assertThat(status.hasIdentity()).isFalse();
		}
	}

}
