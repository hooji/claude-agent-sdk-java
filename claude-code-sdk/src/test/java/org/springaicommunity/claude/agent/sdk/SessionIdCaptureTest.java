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

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.parsing.ParsedMessage;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;
import org.springaicommunity.claude.agent.sdk.types.SystemMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that both clients capture the CLI-assigned session id from inbound messages (the
 * init/system messages and result messages both carry it), making
 * {@link TranscriptAware#getCurrentSessionId()} reflect the real on-disk session.
 */
class SessionIdCaptureTest {

	static final String SID = "16d8b472-d367-4b87-aff9-cb8ca7a2b20e";

	static ParsedMessage initMessage() {
		return ParsedMessage.RegularMessage.of(
				SystemMessage.of("init", Map.of("session_id", SID, "cwd", "/tmp/somewhere")));
	}

	static ParsedMessage resultMessage(String sessionId) {
		return ParsedMessage.RegularMessage.of(ResultMessage.builder()
			.subtype("success")
			.sessionId(sessionId)
			.build());
	}

	@Test
	void syncClientCapturesSessionIdFromInitMessage() {
		DefaultClaudeSyncClient client = new DefaultClaudeSyncClient(Path.of("."), null, null, null, null);

		assertThat(client.getCurrentSessionId()).isNull();
		client.captureSessionId(initMessage());
		assertThat(client.getCurrentSessionId()).isEqualTo(SID);
	}

	@Test
	void syncClientCapturesSessionIdFromResultMessage() {
		DefaultClaudeSyncClient client = new DefaultClaudeSyncClient(Path.of("."), null, null, null, null);

		client.captureSessionId(resultMessage("another-id"));
		assertThat(client.getCurrentSessionId()).isEqualTo("another-id");

		// A result without a session id must not clobber the captured value
		client.captureSessionId(resultMessage(null));
		assertThat(client.getCurrentSessionId()).isEqualTo("another-id");
	}

	@Test
	void asyncClientCapturesSessionIdFromInitMessage() {
		DefaultClaudeAsyncClient client = new DefaultClaudeAsyncClient(Path.of("."), null, null, null, null);

		assertThat(client.getCurrentSessionId()).isNull();
		client.captureSessionId(initMessage());
		assertThat(client.getCurrentSessionId()).isEqualTo(SID);
	}

	@Test
	void asyncClientCapturesSessionIdFromResultMessage() {
		DefaultClaudeAsyncClient client = new DefaultClaudeAsyncClient(Path.of("."), null, null, null, null);

		client.captureSessionId(resultMessage("another-id"));
		assertThat(client.getCurrentSessionId()).isEqualTo("another-id");
	}

	@Test
	void clientsExposeTheirWorkingDirectory() {
		Path dir = Path.of("/some/dir");
		assertThat(new DefaultClaudeSyncClient(dir, null, null, null, null).getWorkingDirectory()).isEqualTo(dir);
		assertThat(new DefaultClaudeAsyncClient(dir, null, null, null, null).getWorkingDirectory()).isEqualTo(dir);
	}

}
