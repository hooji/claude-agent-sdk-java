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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.claude.agent.sdk.test.ClaudeCliTestBase;
import org.springaicommunity.claude.agent.sdk.transcript.Session;
import org.springaicommunity.claude.agent.sdk.transcript.SessionArchive;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live end-to-end test of the background-agent lifecycle against a real {@code claude} CLI:
 * dispatch &rarr; await &rarr; retrieve (via the transcript toolkit) &rarr; archive. Runs only
 * when API credentials are available (see {@link ClaudeCliTestBase}).
 */
class BackgroundAgentIT extends ClaudeCliTestBase {

	@Test
	void dispatchAwaitRetrieveAndArchive(@TempDir Path workDir, @TempDir Path archiveDir) throws Exception {
		BackgroundAgent agent = BackgroundAgents.dispatch("Reply with exactly the single word: pong. Do not use any tools.",
				workDir);

		assertThat(agent.id()).as("short id parsed/resolved").isNotBlank();
		assertThat(agent.sessionId()).as("full session id resolved").isNotBlank();
		assertThat(agent.workingDirectory()).isNotNull();

		// dispatch returns immediately; the agent should be live or already finishing
		assertThat(agent.state()).isIn(BackgroundAgentState.WORKING, BackgroundAgentState.BLOCKED,
				BackgroundAgentState.DONE);

		// poll → terminal
		BackgroundAgentStatus terminal = agent.awaitTerminal(Duration.ofMinutes(3), Duration.ofSeconds(3));
		assertThat(terminal.state()).isEqualTo(BackgroundAgentState.DONE);

		// it shows up in the listing (completed agents stay listed)
		assertThat(BackgroundAgents.list(true)).anyMatch(a -> agent.id().equals(a.id()));

		// structured result via the transcript toolkit (not raw `claude logs`)
		Optional<Session> transcript = agent.transcript();
		assertThat(transcript).as("transcript readable via TranscriptDirectory").isPresent();
		Optional<String> result = agent.result();
		assertThat(result).isPresent();
		assertThat(result.get().toLowerCase()).contains("pong");

		// the finished agent composes with SessionArchive
		Path archive = archiveDir.resolve("agent.zip");
		agent.archiveTo(archive);
		assertThat(Files.exists(archive)).isTrue();
		assertThat(SessionArchive.readManifest(archive).sessionId()).isEqualTo(agent.sessionId());
	}

}
