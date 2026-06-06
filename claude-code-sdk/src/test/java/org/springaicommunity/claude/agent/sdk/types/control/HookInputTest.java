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

package org.springaicommunity.claude.agent.sdk.types.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests deserialization of {@link HookInput} payloads sent by the Claude Code CLI.
 *
 * <p>The CLI evolves independently of the SDK and may add fields to the hook payload (for
 * example, newer CLI versions include an {@code effort} field on {@code PreToolUse}). The
 * SDK must tolerate such unknown fields, otherwise the hook callback fails to deserialize
 * and the registered hook never runs. A plain (strict) {@link ObjectMapper} is used here on
 * purpose to assert the type itself is tolerant, independent of any mapper configuration.
 */
class HookInputTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void deserializesPreToolUseWithUnknownEffortField() {
		// Payload mirrors a PreToolUse hook_callback input from Claude Code CLI 2.1.x,
		// including the "effort" field not modelled by PreToolUseInput.
		Map<String, Object> payload = Map.of("hook_event_name", "PreToolUse", "session_id", "session-123",
				"transcript_path", "/tmp/transcript.jsonl", "cwd", "/work", "permission_mode", "bypassPermissions",
				"tool_name", "Write", "tool_use_id", "toolu_abc", "tool_input", Map.of("file_path", "/work/Hello.java"),
				"effort", "high");

		HookInput input = objectMapper.convertValue(payload, HookInput.class);

		assertThat(input).isInstanceOf(HookInput.PreToolUseInput.class);
		HookInput.PreToolUseInput preToolUse = (HookInput.PreToolUseInput) input;
		assertThat(preToolUse.toolName()).isEqualTo("Write");
		assertThat(preToolUse.getArgument("file_path", String.class)).contains("/work/Hello.java");
	}

	@Test
	void deserializesKnownFieldsWithoutUnknownProperties() {
		Map<String, Object> payload = Map.of("hook_event_name", "PreToolUse", "session_id", "session-123",
				"transcript_path", "/tmp/transcript.jsonl", "cwd", "/work", "permission_mode", "default", "tool_name",
				"Bash", "tool_use_id", "toolu_xyz", "tool_input", Map.of("command", "ls"));

		HookInput input = objectMapper.convertValue(payload, HookInput.class);

		assertThat(input).isInstanceOf(HookInput.PreToolUseInput.class);
		assertThat(((HookInput.PreToolUseInput) input).toolName()).isEqualTo("Bash");
	}

}
