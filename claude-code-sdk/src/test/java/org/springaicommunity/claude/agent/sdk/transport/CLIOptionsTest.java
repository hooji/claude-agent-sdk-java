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

package org.springaicommunity.claude.agent.sdk.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CLIOptions.Builder} convenience methods.
 */
class CLIOptionsTest {

	@Test
	@DisplayName("otelLogRawApiBodiesDirectory should set OTEL_LOG_RAW_API_BODIES with a file: prefix")
	void otelLogRawApiBodiesDirectorySetsPrefixedEnvVar() {
		CLIOptions options = CLIOptions.builder().otelLogRawApiBodiesDirectory("/var/log/claude-raw").build();

		assertThat(options.getEnv()).containsEntry("OTEL_LOG_RAW_API_BODIES", "file:/var/log/claude-raw");
	}

	@Test
	@DisplayName("otelLogRawApiBodiesDirectory should also enable telemetry and a logs exporter so it actually works")
	void otelLogRawApiBodiesDirectorySetsPrerequisiteEnvVars() {
		CLIOptions options = CLIOptions.builder().otelLogRawApiBodiesDirectory("/var/log/claude-raw").build();

		assertThat(options.getEnv()).containsEntry("CLAUDE_CODE_ENABLE_TELEMETRY", "1")
			.containsEntry("OTEL_LOGS_EXPORTER", "console")
			.containsEntry("OTEL_LOG_RAW_API_BODIES", "file:/var/log/claude-raw");
	}

	@Test
	@DisplayName("otelLogRawApiBodiesDirectory should let a later env() call override its defaults")
	void otelLogRawApiBodiesDirectoryDefaultsAreOverridable() {
		CLIOptions options = CLIOptions.builder()
			.otelLogRawApiBodiesDirectory("/tmp/raw-bodies")
			.env("OTEL_LOGS_EXPORTER", "otlp")
			.build();

		assertThat(options.getEnv()).containsEntry("CLAUDE_CODE_ENABLE_TELEMETRY", "1")
			.containsEntry("OTEL_LOG_RAW_API_BODIES", "file:/tmp/raw-bodies")
			.containsEntry("OTEL_LOGS_EXPORTER", "otlp");
	}

	@Test
	@DisplayName("oauthToken should inject CLAUDE_CODE_OAUTH_TOKEN into the subprocess env")
	void oauthTokenInjectsEnvVar() {
		CLIOptions options = CLIOptions.builder().oauthToken("sk-ant-oat01-xyz").build();
		assertThat(options.getEnv()).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-xyz");
	}

	@Test
	@DisplayName("oauthToken(null) should be a no-op so it can be plumbed unconditionally")
	void oauthTokenNullIsNoOp() {
		CLIOptions options = CLIOptions.builder().oauthToken(null).build();
		assertThat(options.getEnv()).doesNotContainKey("CLAUDE_CODE_OAUTH_TOKEN");
	}

	@Test
	@DisplayName("oauthToken should reject blank tokens")
	void oauthTokenRejectsBlank() {
		assertThatThrownBy(() -> CLIOptions.builder().oauthToken("  ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("oauthToken should lose to a later explicit env() write (last wins)")
	void oauthTokenOverridableByExplicitEnv() {
		CLIOptions options = CLIOptions.builder().oauthToken("first").env("CLAUDE_CODE_OAUTH_TOKEN", "second").build();
		assertThat(options.getEnv()).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "second");
	}

}
