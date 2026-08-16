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

package org.springaicommunity.claude.agent.sdk.sessions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.claude.agent.sdk.sessions.ClaudeCloudSessions.CloudSession;
import org.springaicommunity.claude.agent.sdk.sessions.ClaudeCloudSessions.Page;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClaudeCloudSessions#parsePage(String)} and {@link CloudSession}.
 * The fixture mirrors the shape of {@code GET /v1/code/sessions} responses (the endpoint
 * behind {@code claude --teleport}).
 */
class ClaudeCloudSessionsTest {

	/** One full session entry, shaped like the wire format. */
	private static final String SESSION = """
			{
			  "id": "cse_0123456789abcdef",
			  "title": "Integrate cloud sessions client",
			  "status": "active",
			  "status_bucket": "review_ready",
			  "worker_status": "idle",
			  "connection_status": "disconnected",
			  "environment_id": "env_abc123",
			  "environment_kind": "anthropic_cloud",
			  "created_at": "2026-07-20T10:15:30Z",
			  "last_event_at": "2026-07-21T11:22:33Z",
			  "unread": true,
			  "user_message_count": "7",
			  "tags": ["remote-control-sdk"],
			  "participants": [],
			  "relations": [],
			  "config": {
			    "model": "claude-opus-4-8",
			    "effort_level": "max",
			    "origin": "desktop_app",
			    "mcp_connector_ids": [],
			    "sources": [
			      {
			        "type": "git_repository",
			        "url": "https://github.com/hooji/claude-agent-sdk-java",
			        "revision": "refs/heads/main",
			        "sparse_checkout_paths": []
			      }
			    ],
			    "outcomes": [
			      {
			        "type": "git_repository",
			        "git_info": {
			          "type": "github",
			          "host": "",
			          "repo": "hooji/claude-agent-sdk-java",
			          "ref": null,
			          "branches": ["claude/some-branch"]
			        }
			      }
			    ]
			  },
			  "external_metadata": {
			    "container_cc_version": "2.1.210",
			    "last_served_model": "claude-opus-4-8",
			    "current_branches": {"/home/user/repo": "claude/some-branch"},
			    "post_turn_summary": {
			      "needs_action": "",
			      "status_category": "review_ready",
			      "status_detail": "PR opened, awaiting review"
			    }
			  }
			}""";

	private static CloudSession parseOne(String entryJson) throws IOException {
		Page page = ClaudeCloudSessions.parsePage("{\"data\":[" + entryJson + "]}");
		assertThat(page.sessions()).hasSize(1);
		return page.sessions().get(0);
	}

	@Nested
	class TypedFields {

		@Test
		void parsesFullEntry() throws IOException {
			CloudSession s = parseOne(SESSION);
			assertThat(s.id()).isEqualTo("cse_0123456789abcdef");
			assertThat(s.title()).isEqualTo("Integrate cloud sessions client");
			assertThat(s.status()).isEqualTo("active");
			assertThat(s.statusBucket()).isEqualTo("review_ready");
			assertThat(s.workerStatus()).isEqualTo("idle");
			assertThat(s.connectionStatus()).isEqualTo("disconnected");
			assertThat(s.environmentId()).isEqualTo("env_abc123");
			assertThat(s.environmentKind()).isEqualTo("anthropic_cloud");
			assertThat(s.createdAt()).isEqualTo(Instant.parse("2026-07-20T10:15:30Z"));
			assertThat(s.lastEventAt()).isEqualTo(Instant.parse("2026-07-21T11:22:33Z"));
			assertThat(s.unread()).isTrue();
			// user_message_count arrives as a string on the wire
			assertThat(s.userMessageCount()).isEqualTo(7);
			assertThat(s.tags()).containsExactly("remote-control-sdk");
		}

		@Test
		void parsesConfigAndOutcomes() throws IOException {
			CloudSession s = parseOne(SESSION);
			assertThat(s.config().model()).isEqualTo("claude-opus-4-8");
			assertThat(s.config().effortLevel()).isEqualTo("max");
			assertThat(s.config().origin()).isEqualTo("desktop_app");
			assertThat(s.config().sources()).hasSize(1);
			assertThat(s.config().sources().get(0).url()).isEqualTo("https://github.com/hooji/claude-agent-sdk-java");
			assertThat(s.config().outcomes()).hasSize(1);
			assertThat(s.config().outcomes().get(0).gitInfo().repo()).isEqualTo("hooji/claude-agent-sdk-java");
			assertThat(s.config().outcomes().get(0).gitInfo().branches()).containsExactly("claude/some-branch");
		}

		@Test
		void parsesExternalMetadata() throws IOException {
			CloudSession s = parseOne(SESSION);
			assertThat(s.externalMetadata().containerCcVersion()).isEqualTo("2.1.210");
			assertThat(s.externalMetadata().currentBranches()).containsEntry("/home/user/repo", "claude/some-branch");
			assertThat(s.externalMetadata().postTurnSummary().statusCategory()).isEqualTo("review_ready");
			assertThat(s.externalMetadata().postTurnSummary().statusDetail()).isEqualTo("PR opened, awaiting review");
		}

		@Test
		void toleratesMinimalEntry() throws IOException {
			CloudSession s = parseOne("{\"id\": \"cse_min\"}");
			assertThat(s.id()).isEqualTo("cse_min");
			assertThat(s.title()).isNull();
			assertThat(s.createdAt()).isNull();
			assertThat(s.userMessageCount()).isZero();
			assertThat(s.tags()).isEmpty();
			assertThat(s.config()).isNull();
			assertThat(s.externalMetadata()).isNull();
		}

	}

	@Nested
	class PageFields {

		@Test
		void exposesCursorAndResumeToken() throws IOException {
			Page page = ClaudeCloudSessions.parsePage("{\"data\":[],\"next_cursor\":\"abc\",\"resume_token\":\"tok\"}");
			assertThat(page.sessions()).isEmpty();
			assertThat(page.nextCursor()).isEqualTo("abc");
			assertThat(page.resumeToken()).isEqualTo("tok");
		}

		@Test
		void lastPageHasNullCursor() throws IOException {
			Page page = ClaudeCloudSessions.parsePage("{\"data\":[]}");
			assertThat(page.nextCursor()).isNull();
			assertThat(page.resumeToken()).isNull();
		}

	}

	@Nested
	class Helpers {

		@Test
		void workerStatusHelpers() throws IOException {
			CloudSession idle = parseOne(SESSION);
			assertThat(idle.isIdle()).isTrue();
			assertThat(idle.requiresAction()).isFalse();

			CloudSession blocked = parseOne("{\"id\": \"cse_b\", \"worker_status\": \"requires_action\"}");
			assertThat(blocked.isIdle()).isFalse();
			assertThat(blocked.requiresAction()).isTrue();

			CloudSession working = parseOne("{\"id\": \"cse_w\", \"worker_status\": \"running\"}");
			assertThat(working.isIdle()).isFalse();
			assertThat(working.requiresAction()).isFalse();
		}

	}

	@Nested
	class FutureFieldPreservation {

		@Test
		void unknownFieldsSurviveInAllValues() throws IOException {
			String withFutureFields = """
					{
					  "id": "cse_f",
					  "brand_new_field": "kept",
					  "nested": {"deep": {"value": 42}, "empty": {}},
					  "list": ["x", "y"],
					  "gone": null
					}""";
			CloudSession s = parseOne(withFutureFields);
			assertThat(s.allValues()).containsEntry("brand_new_field", "kept")
				.containsEntry("nested.deep.value", "42")
				.containsEntry("list.0", "x")
				.containsEntry("list.1", "y")
				.containsEntry("gone", "null");
			// Empty objects contribute no entries
			assertThat(s.allValues().keySet()).noneMatch(k -> k.startsWith("nested.empty"));
		}

		@Test
		void allValuesMirrorsTypedFields() throws IOException {
			CloudSession s = parseOne(SESSION);
			assertThat(s.allValues()).containsEntry("id", "cse_0123456789abcdef")
				.containsEntry("worker_status", "idle")
				.containsEntry("config.sources.0.url", "https://github.com/hooji/claude-agent-sdk-java")
				.containsEntry("external_metadata.post_turn_summary.status_category", "review_ready")
				.containsEntry("tags.0", "remote-control-sdk")
				.containsEntry("config.outcomes.0.git_info.ref", "null");
		}

		@Test
		void allValuesIsUnmodifiable() throws IOException {
			CloudSession s = parseOne(SESSION);
			assertThatThrownBy(() -> s.allValues().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
		}

	}

	@Nested
	class Errors {

		@Test
		void rejectsMalformedJson() {
			assertThatThrownBy(() -> ClaudeCloudSessions.parsePage("not json at all")).isInstanceOf(IOException.class);
		}

	}

	/**
	 * The update methods issue {@code PUT /v1/code/sessions/<id>}; these tests cover the
	 * request-body construction and the validations that fire before any network I/O.
	 */
	@Nested
	class Updating {

		@Test
		void tagsBodyWithAddsOnly() throws IOException {
			JsonNode body = new ObjectMapper()
				.readTree(ClaudeCloudSessions.tagsUpdateBody(List.of("group-a", "color:blue"), null));
			assertThat(body.path("add_tags").get(0).asText()).isEqualTo("group-a");
			assertThat(body.path("add_tags").get(1).asText()).isEqualTo("color:blue");
			assertThat(body.has("remove_tags")).isFalse(); // empty list omitted, like the
															// CLI
		}

		@Test
		void tagsBodyWithRemovesOnly() throws IOException {
			JsonNode body = new ObjectMapper()
				.readTree(ClaudeCloudSessions.tagsUpdateBody(List.of(), List.of("color:red")));
			assertThat(body.has("add_tags")).isFalse();
			assertThat(body.path("remove_tags").get(0).asText()).isEqualTo("color:red");
		}

		@Test
		void tagsBodyWithBoth() throws IOException {
			JsonNode body = new ObjectMapper().readTree(ClaudeCloudSessions.tagsUpdateBody(List.of("a"), List.of("b")));
			assertThat(body.path("add_tags").size()).isEqualTo(1);
			assertThat(body.path("remove_tags").size()).isEqualTo(1);
		}

		@Test
		void emptyUpdateIsRejected() {
			assertThatThrownBy(() -> ClaudeCloudSessions.updateSessionTags("tok", "cse_x", List.of(), null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("nothing to change");
		}

		@Test
		void blankTitleAndSessionIdAreRejectedBeforeAnyIo() {
			assertThatThrownBy(() -> ClaudeCloudSessions.updateSessionTitle("tok", "cse_x", " "))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ClaudeCloudSessions.updateSessionTitle("tok", "", "title"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ClaudeCloudSessions.updateSessionTags("tok", " ", List.of("a"), null))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void colorTagPrefixMatchesTheCli() {
			assertThat(ClaudeCloudSessions.COLOR_TAG_PREFIX).isEqualTo("color:");
		}

	}

}
