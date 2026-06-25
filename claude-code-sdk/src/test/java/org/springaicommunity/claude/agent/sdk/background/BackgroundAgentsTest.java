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

package org.springaicommunity.claude.agent.sdk.background;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the background-agent parsing and command building (no CLI). */
class BackgroundAgentsTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void parsesShortIdFromDispatchBanner() {
		String banner = "Starting background service…\n" + "backgrounded · 43a5daa7\n"
				+ "  claude agents             list sessions\n" + "  claude logs 43a5daa7      show recent output\n";
		assertThat(BackgroundAgents.parseBannerId(banner)).isEqualTo("43a5daa7");
		assertThat(BackgroundAgents.parseBannerId("no id here")).isNull();
		assertThat(BackgroundAgents.parseBannerId(null)).isNull();
	}

	@Test
	void parsesBackgroundEntryFromAgentsJson() throws Exception {
		String json = "{\"pid\":2288,\"id\":\"43a5daa7\",\"cwd\":\"/home/user\",\"kind\":\"background\","
				+ "\"startedAt\":1782355788832,\"sessionId\":\"43a5daa7-040a-4a88-bf1b-9632bfb532c7\","
				+ "\"name\":\"do the thing\",\"status\":\"idle\",\"state\":\"done\"}";
		BackgroundAgentStatus s = BackgroundAgentStatus.from(mapper.readTree(json));

		assertThat(s.id()).isEqualTo("43a5daa7");
		assertThat(s.sessionId()).isEqualTo("43a5daa7-040a-4a88-bf1b-9632bfb532c7");
		assertThat(s.cwd()).isEqualTo(Path.of("/home/user"));
		assertThat(s.isBackground()).isTrue();
		assertThat(s.state()).isEqualTo(BackgroundAgentState.DONE);
		assertThat(s.state().isTerminal()).isTrue();
		assertThat(s.activity()).isEqualTo("idle");
		assertThat(s.pid()).isEqualTo(2288);
		assertThat(s.startedAt()).isEqualTo(Instant.ofEpochMilli(1782355788832L));
	}

	@Test
	void interactiveEntryIsNotBackgroundAndHasUnknownState() throws Exception {
		String json = "{\"pid\":553,\"cwd\":\"/home/user\",\"kind\":\"interactive\","
				+ "\"startedAt\":1782355668948,\"sessionId\":\"3f59427e-67e9-520a-a983-ee707a91f849\"}";
		BackgroundAgentStatus s = BackgroundAgentStatus.from(mapper.readTree(json));

		assertThat(s.isBackground()).isFalse();
		assertThat(s.state()).isEqualTo(BackgroundAgentState.UNKNOWN);
		assertThat(s.state().isTerminal()).isFalse();
		assertThat(s.id()).isNull();
	}

	@Test
	void stateMappingIsForwardCompatible() {
		assertThat(BackgroundAgentState.fromWire("working")).isEqualTo(BackgroundAgentState.WORKING);
		assertThat(BackgroundAgentState.fromWire("done")).isEqualTo(BackgroundAgentState.DONE);
		assertThat(BackgroundAgentState.fromWire("failed")).isEqualTo(BackgroundAgentState.FAILED);
		assertThat(BackgroundAgentState.fromWire("stopped")).isEqualTo(BackgroundAgentState.STOPPED);
		assertThat(BackgroundAgentState.fromWire("some-future-state")).isEqualTo(BackgroundAgentState.UNKNOWN);
		assertThat(BackgroundAgentState.fromWire(null)).isEqualTo(BackgroundAgentState.UNKNOWN);
	}

	@Test
	void buildsDispatchCommandWithForwardedConfigAndPromptLast() {
		CLIOptions opts = CLIOptions.builder()
			.model("claude-sonnet-4-6")
			.systemPrompt("be terse")
			.allowedTools(List.of("Read", "Bash"))
			.maxBudgetUsd(0.50)
			.addDir(Path.of("/extra"))
			.build();

		List<String> cmd = BackgroundAgents.buildDispatchCommand("claude", "do X", opts);

		assertThat(cmd).startsWith("claude", "--bg");
		assertThat(cmd).containsSequence("--model", "claude-sonnet-4-6");
		assertThat(cmd).containsSequence("--system-prompt", "be terse");
		assertThat(cmd).containsSequence("--allowedTools", "Read,Bash");
		assertThat(cmd).containsSequence("--max-budget-usd", "0.5");
		assertThat(cmd).containsSequence("--add-dir", "/extra");
		// the prompt is the final positional argument
		assertThat(cmd.get(cmd.size() - 1)).isEqualTo("do X");
		// streaming-only flags are never forwarded to a background dispatch
		assertThat(cmd).doesNotContain("--output-format", "--input-format", "--include-partial-messages");
	}

	@Test
	void buildsMinimalCommandWhenNoOptions() {
		assertThat(BackgroundAgents.buildDispatchCommand("claude", "hello", null)).containsExactly("claude", "--bg",
				"hello");
	}

}
