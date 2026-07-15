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

package org.springaicommunity.claude.agent.sdk.sessions;

import org.springaicommunity.claude.agent.sdk.sessions.ClaudeLocalSessions.LocalSession;
import org.springaicommunity.claude.agent.sdk.test.ClaudeCliTestBase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ClaudeLocalSessions} against the real Claude CLI. The
 * session list of the machine running the tests is unknown (possibly empty), so
 * assertions are structural; when the tests themselves run inside a Claude session
 * (CLAUDE_CODE_SESSION_ID set), that session is asserted to be present.
 */
class ClaudeLocalSessionsIT extends ClaudeCliTestBase {

	@Override
	protected boolean requiresApi() {
		// `claude agents --json` queries the local supervisor; no model access involved
		return false;
	}

	@Test
	void listsWithoutErrorAndParsesCleanly() throws IOException {
		List<LocalSession> active = ClaudeLocalSessions.listLocalSessions();
		List<LocalSession> all = ClaudeLocalSessions.listLocalSessions(true);
		for (LocalSession s : all) {
			assertThat(s.kind()).as("kind of %s", s).isIn("interactive", "background");
			assertThat(s.cwd()).as("cwd of %s", s).isNotBlank();
			assertThat(s.allValues()).as("allValues of %s", s).isNotEmpty();
			// The flattened map mirrors the typed fields
			assertThat(s.allValues().get("kind")).isEqualTo(s.kind());
			if (s.sessionId() != null) {
				assertThat(s.allValues().get("sessionId")).isEqualTo(s.sessionId());
			}
		}
		// Point-in-time race aside, the active list is contained in the full list
		assertThat(active.size()).isLessThanOrEqualTo(all.size() + 1);
	}

	@Test
	void seesTheEnclosingClaudeSessionWhenInsideOne() throws IOException {
		String enclosingSessionId = System.getenv("CLAUDE_CODE_SESSION_ID");
		if (enclosingSessionId == null || enclosingSessionId.isBlank()) {
			return; // not running inside a Claude session; nothing to assert
		}
		List<LocalSession> sessions = ClaudeLocalSessions.listLocalSessions();
		assertThat(sessions).extracting(LocalSession::sessionId).contains(enclosingSessionId);
		LocalSession self = sessions.stream()
			.filter(s -> enclosingSessionId.equals(s.sessionId()))
			.findFirst()
			.orElseThrow();
		assertThat(self.isInteractive()).isTrue();
		assertThat(self.pid()).isPositive();
		assertThat(self.startedAt()).isNotNull();
	}

}
