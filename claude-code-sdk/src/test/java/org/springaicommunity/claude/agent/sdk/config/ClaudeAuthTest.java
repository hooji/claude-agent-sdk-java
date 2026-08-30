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

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.config.ClaudeAuth.AuthStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parse tests for {@link ClaudeAuth} against the two wire shapes observed on CLI 2.1.251:
 * a full-identity interactive claude.ai login, and the state-only output of
 * injected-token auth.
 */
class ClaudeAuthTest {

	@Test
	void parsesInteractiveLoginWithFullIdentity() throws IOException {
		String json = """
				{
				  "loggedIn": true,
				  "authMethod": "claude.ai",
				  "apiProvider": "firstParty",
				  "analyticsDisabled": false,
				  "projectsDirectory": "/Users/alice/.claude/projects",
				  "email": "alice@example.com",
				  "orgId": "12c469a8-0a3a-4a29-8a21-5545011ef22f",
				  "orgName": "alice@example.com's Organization",
				  "subscriptionType": "max"
				}
				""";

		AuthStatus status = ClaudeAuth.parseStatus(json);

		assertThat(status.loggedIn()).isTrue();
		assertThat(status.authMethod()).isEqualTo("claude.ai");
		assertThat(status.apiProvider()).isEqualTo("firstParty");
		assertThat(status.email()).isEqualTo("alice@example.com");
		assertThat(status.orgId()).isEqualTo("12c469a8-0a3a-4a29-8a21-5545011ef22f");
		assertThat(status.orgName()).isEqualTo("alice@example.com's Organization");
		assertThat(status.subscriptionType()).isEqualTo("max");
		assertThat(status.hasIdentity()).isTrue();

		// Untyped fields survive in the flattened raw map
		assertThat(status.allValues()).containsEntry("projectsDirectory", "/Users/alice/.claude/projects")
			.containsEntry("analyticsDisabled", "false")
			.containsEntry("email", "alice@example.com");
	}

	@Test
	void parsesTokenAuthWithoutIdentity() throws IOException {
		String json = """
				{
				  "loggedIn": true,
				  "authMethod": "oauth_token",
				  "apiProvider": "firstParty",
				  "analyticsDisabled": false,
				  "projectsDirectory": "/root/.claude/projects"
				}
				""";

		AuthStatus status = ClaudeAuth.parseStatus(json);

		assertThat(status.loggedIn()).isTrue();
		assertThat(status.authMethod()).isEqualTo("oauth_token");
		assertThat(status.email()).isNull();
		assertThat(status.orgId()).isNull();
		assertThat(status.orgName()).isNull();
		assertThat(status.subscriptionType()).isNull();
		assertThat(status.hasIdentity()).isFalse();
	}

	@Test
	void parsesLoggedOutState() throws IOException {
		AuthStatus status = ClaudeAuth.parseStatus("{\"loggedIn\": false}");

		assertThat(status.loggedIn()).isFalse();
		assertThat(status.hasIdentity()).isFalse();
		assertThat(status.allValues()).containsEntry("loggedIn", "false");
	}

	@Test
	void preservesUnknownFutureFieldsInAllValues() throws IOException {
		String json = """
				{"loggedIn": true, "authMethod": "claude.ai", "newFangled": {"nested": 42}}
				""";

		AuthStatus status = ClaudeAuth.parseStatus(json);

		assertThat(status.allValues()).containsEntry("newFangled.nested", "42");
	}

	@Test
	void rejectsNonObjectOutput() {
		assertThatThrownBy(() -> ClaudeAuth.parseStatus("[]")).isInstanceOf(IOException.class)
			.hasMessageContaining("JSON object");
		assertThatThrownBy(() -> ClaudeAuth.parseStatus("")).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> ClaudeAuth.parseStatus(null)).isInstanceOf(IOException.class);
	}

}
