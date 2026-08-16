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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClaudeLocalSessions#parseSessions(String)} and
 * {@link LocalSession}. The fixtures are verbatim captures of
 * {@code claude agents --json [--all]} output from Claude CLI 2.1.210.
 */
class ClaudeLocalSessionsTest {

	/** A live interactive session, as captured from the CLI. */
	private static final String INTERACTIVE = """
			{
			  "pid": 553,
			  "cwd": "/home/user/claude-agent-sdk-java",
			  "kind": "interactive",
			  "startedAt": 1784152250583,
			  "sessionId": "b7cc4933-56d9-51df-8477-4a783e46493f",
			  "name": "claude-agent-sdk-java-01"
			}""";

	/** A background agent just after dispatch (no pid yet), as captured from the CLI. */
	private static final String BACKGROUND_WORKING = """
			{
			  "id": "49e6d19c",
			  "cwd": "/tmp/scratch/bgtest",
			  "kind": "background",
			  "startedAt": 1784152598998,
			  "sessionId": "49e6d19c-9e4a-4050-b423-4ea53c48cdb9",
			  "name": "Reply with exactly the word: pong",
			  "state": "working"
			}""";

	/** A finished-but-still-resident background agent, as captured from the CLI. */
	private static final String BACKGROUND_DONE_RESIDENT = """
			{
			  "pid": 6057,
			  "id": "49e6d19c",
			  "cwd": "/tmp/scratch/bgtest",
			  "kind": "background",
			  "startedAt": 1784152601792,
			  "sessionId": "49e6d19c-9e4a-4050-b423-4ea53c48cdb9",
			  "name": "a task-derived label…",
			  "status": "idle",
			  "state": "done"
			}""";

	/** An exited background agent (only listed with --all), as captured from the CLI. */
	private static final String BACKGROUND_EXITED = """
			{
			  "id": "49e6d19c",
			  "cwd": "/tmp/scratch/bgtest",
			  "kind": "background",
			  "startedAt": 1784152598998,
			  "sessionId": "49e6d19c-9e4a-4050-b423-4ea53c48cdb9",
			  "name": "a task-derived label…",
			  "state": "done"
			}""";

	private static LocalSession parseOne(String entryJson) throws IOException {
		List<LocalSession> sessions = ClaudeLocalSessions.parseSessions("[" + entryJson + "]");
		assertThat(sessions).hasSize(1);
		return sessions.get(0);
	}

	@Nested
	class TypedFields {

		@Test
		void parsesInteractiveEntry() throws IOException {
			LocalSession s = parseOne(INTERACTIVE);
			assertThat(s.pid()).isEqualTo(553);
			assertThat(s.cwd()).isEqualTo("/home/user/claude-agent-sdk-java");
			assertThat(s.kind()).isEqualTo("interactive");
			assertThat(s.startedAt()).isEqualTo(Instant.ofEpochMilli(1784152250583L));
			assertThat(s.sessionId()).isEqualTo("b7cc4933-56d9-51df-8477-4a783e46493f");
			assertThat(s.name()).isEqualTo("claude-agent-sdk-java-01");
			// Fields not present on interactive entries
			assertThat(s.id()).isNull();
			assertThat(s.state()).isNull();
			assertThat(s.status()).isNull();
		}

		@Test
		void parsesWorkingBackgroundEntry() throws IOException {
			LocalSession s = parseOne(BACKGROUND_WORKING);
			assertThat(s.id()).isEqualTo("49e6d19c");
			assertThat(s.state()).isEqualTo("working");
			assertThat(s.pid()).isNull();
			assertThat(s.status()).isNull();
		}

		@Test
		void parsesResidentDoneBackgroundEntry() throws IOException {
			LocalSession s = parseOne(BACKGROUND_DONE_RESIDENT);
			assertThat(s.pid()).isEqualTo(6057);
			assertThat(s.state()).isEqualTo("done");
			assertThat(s.status()).isEqualTo("idle");
		}

		@Test
		void parsesExitedBackgroundEntry() throws IOException {
			LocalSession s = parseOne(BACKGROUND_EXITED);
			assertThat(s.pid()).isNull();
			assertThat(s.status()).isNull();
			assertThat(s.state()).isEqualTo("done");
		}

		@Test
		void parsesFullCapturedArrayInOrder() throws IOException {
			List<LocalSession> sessions = ClaudeLocalSessions
				.parseSessions("[" + INTERACTIVE + "," + BACKGROUND_DONE_RESIDENT + "]");
			assertThat(sessions).hasSize(2);
			assertThat(sessions.get(0).isInteractive()).isTrue();
			assertThat(sessions.get(1).isBackground()).isTrue();
		}

		@Test
		void parsesEmptyArray() throws IOException {
			assertThat(ClaudeLocalSessions.parseSessions("[]")).isEmpty();
		}

	}

	@Nested
	class FutureFieldPreservation {

		@Test
		void unknownFieldsSurviveInAllValues() throws IOException {
			String withFutureFields = """
					[{
					  "pid": 1,
					  "kind": "interactive",
					  "sessionId": "s-1",
					  "entrypoint": "remote",
					  "version": "9.9.9",
					  "labels": ["a", "b"],
					  "meta": {"nested": {"deep": true}, "empty": {}},
					  "gone": null
					}]""";
			LocalSession s = ClaudeLocalSessions.parseSessions(withFutureFields).get(0);
			// Typed fields unaffected
			assertThat(s.pid()).isEqualTo(1);
			assertThat(s.sessionId()).isEqualTo("s-1");
			// Unknown scalar, array, and nested-object fields all preserved
			assertThat(s.allValues()).containsEntry("entrypoint", "remote")
				.containsEntry("version", "9.9.9")
				.containsEntry("labels.0", "a")
				.containsEntry("labels.1", "b")
				.containsEntry("meta.nested.deep", "true")
				.containsEntry("gone", "null");
			// Empty objects contribute no entries
			assertThat(s.allValues().keySet()).noneMatch(k -> k.startsWith("meta.empty"));
		}

		@Test
		void allValuesMirrorsTypedFields() throws IOException {
			LocalSession s = parseOne(INTERACTIVE);
			assertThat(s.allValues()).containsEntry("pid", "553")
				.containsEntry("kind", "interactive")
				.containsEntry("startedAt", "1784152250583")
				.containsEntry("sessionId", "b7cc4933-56d9-51df-8477-4a783e46493f");
		}

		@Test
		void allValuesIsUnmodifiable() throws IOException {
			LocalSession s = parseOne(INTERACTIVE);
			assertThatThrownBy(() -> s.allValues().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
		}

	}

	@Nested
	class Helpers {

		@Test
		void kindHelpers() throws IOException {
			assertThat(parseOne(INTERACTIVE).isInteractive()).isTrue();
			assertThat(parseOne(INTERACTIVE).isBackground()).isFalse();
			assertThat(parseOne(BACKGROUND_WORKING).isBackground()).isTrue();
			assertThat(parseOne(BACKGROUND_WORKING).isInteractive()).isFalse();
		}

		@Test
		void terminalStateDetection() throws IOException {
			assertThat(parseOne(BACKGROUND_WORKING).isTerminal()).isFalse();
			assertThat(parseOne(BACKGROUND_DONE_RESIDENT).isTerminal()).isTrue();
			assertThat(parseOne(BACKGROUND_EXITED).isTerminal()).isTrue();
			// Interactive entries carry no state and are never terminal
			assertThat(parseOne(INTERACTIVE).isTerminal()).isFalse();
		}

	}

	@Nested
	class Errors {

		@Test
		void rejectsNonArrayRoot() {
			assertThatThrownBy(() -> ClaudeLocalSessions.parseSessions("{\"error\":\"nope\"}"))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("Expected a JSON array");
		}

		@Test
		void rejectsMalformedJson() {
			assertThatThrownBy(() -> ClaudeLocalSessions.parseSessions("not json at all"))
				.isInstanceOf(IOException.class);
		}

	}

}
