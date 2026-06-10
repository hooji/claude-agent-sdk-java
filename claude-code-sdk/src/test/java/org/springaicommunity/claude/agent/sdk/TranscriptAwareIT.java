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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.claude.agent.sdk.config.PermissionMode;
import org.springaicommunity.claude.agent.sdk.test.ClaudeCliTestBase;
import org.springaicommunity.claude.agent.sdk.transcript.Session;
import org.springaicommunity.claude.agent.sdk.transcript.TranscriptDirectory;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of {@link TranscriptAware}: runs a real session in a fresh working
 * directory, then retrieves its on-disk history straight from the client.
 */
class TranscriptAwareIT extends ClaudeCliTestBase {

	@Test
	@DisplayName("Client exposes its session's on-disk history after a live conversation")
	void clientExposesSessionHistory(@TempDir Path workingDir) throws Exception {
		try (ClaudeSyncClient client = ClaudeClient.sync()
			.workingDirectory(workingDir)
			.claudePath(getClaudeCliPath())
			.model(CLIOptions.MODEL_HAIKU)
			// No tools are used; DEFAULT also works in environments where the CLI rejects
			// --dangerously-skip-permissions (e.g. running as root).
			.permissionMode(PermissionMode.DEFAULT)
			.timeout(Duration.ofMinutes(2))
			.build()) {

			assertThat(client.getWorkingDirectory()).isEqualTo(workingDir);
			assertThat(client.getCurrentSessionId()).isNull(); // nothing observed yet

			String answer = client.connectText("Remember: the secret word is XYZZY. Reply with only: ok");
			assertThat(answer).isNotBlank();

			// The CLI-assigned session id was captured from the wire.
			String sessionId = client.getCurrentSessionId();
			assertThat(sessionId).isNotNull().isNotEqualTo("default");

			// The transcript was written to the mapped projects folder...
			Path expectedDir = TranscriptDirectory.projectsDirFor(workingDir);
			assertThat(Files.isRegularFile(expectedDir.resolve(sessionId + ".jsonl")))
				.as("transcript file for session %s under %s", sessionId, expectedDir)
				.isTrue();

			// ...and is retrievable directly from the client, containing this conversation.
			Session session = awaitSession(client);
			assertThat(session.sessionId()).isEqualTo(sessionId);
			assertThat(session.messages()).isNotEmpty();
			assertThat(session.entries().toString()).contains("XYZZY");

			// The directory view agrees.
			TranscriptDirectory transcripts = client.getTranscriptDirectory();
			assertThat(transcripts.byId(sessionId)).isPresent();
			assertThat(transcripts.mostRecentSession().sessionId()).isEqualTo(sessionId);
			assertThat(client.getSession(sessionId).sessionId()).isEqualTo(sessionId);
		}
	}

	/** The CLI flushes the transcript asynchronously; allow it a few seconds. */
	private static Session awaitSession(ClaudeSyncClient client) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			Session session = client.getSession();
			if (session != null) {
				return session;
			}
			Thread.sleep(100);
		}
		return client.getSession();
	}

}
