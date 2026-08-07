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

package org.springaicommunity.claude.agent.sdk.transcript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the official Claude Code session labels — the {@code {"type":"tag"}} /
 * {@code {"type":"custom-title"}} / {@code {"type":"ai-title"}} transcript lines behind the
 * desktop app's custom groups and renames — as read and written by {@link Session} /
 * {@link SessionLabels}. The line format and semantics (append-only, last line wins, empty
 * value clears) mirror the CLI's own {@code tagSession} / {@code renameSession}.
 */
class SessionLabelsTest {

	static final String ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0001";

	static final ObjectMapper MAPPER = new ObjectMapper();

	/** Writes a minimal transcript with the given extra lines appended after two messages. */
	Path writeTranscript(Path dir, String sessionId, String... extraLines) throws IOException {
		Path file = dir.resolve(sessionId + ".jsonl");
		StringBuilder sb = new StringBuilder();
		sb.append("{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"").append(sessionId)
				.append("\",\"cwd\":\"/work/proj\",\"timestamp\":\"2026-08-01T10:00:00Z\",")
				.append("\"message\":{\"role\":\"user\",\"content\":\"hello\"}}\n");
		sb.append("{\"type\":\"assistant\",\"uuid\":\"u2\",\"parentUuid\":\"u1\",\"sessionId\":\"").append(sessionId)
				.append("\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n");
		for (String line : extraLines) {
			sb.append(line).append("\n");
		}
		Files.writeString(file, sb.toString());
		return file;
	}

	static String tagLine(String tag) {
		return "{\"type\":\"tag\",\"tag\":\"" + tag + "\",\"sessionId\":\"" + ID + "\"}";
	}

	Session load(Path dir, boolean lite) throws IOException {
		return TranscriptDirectory.load(dir.toString(), lite).byId(ID).orElseThrow();
	}

	@Nested
	class Reading {

		@Test
		void extractsTagAndTitles(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID, tagLine("my-group"),
					"{\"type\":\"custom-title\",\"customTitle\":\"My run\",\"sessionId\":\"" + ID + "\"}",
					"{\"type\":\"ai-title\",\"aiTitle\":\"Auto title\",\"sessionId\":\"" + ID + "\"}");
			Session s = load(dir, false);
			assertThat(s.tag()).isEqualTo("my-group");
			assertThat(s.customTitle()).isEqualTo("My run");
			assertThat(s.aiTitle()).isEqualTo("Auto title");
			assertThat(s.displayTitle()).isEqualTo("My run"); // custom wins over generated
		}

		@Test
		void noLabelsMeansNulls(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID);
			Session s = load(dir, false);
			assertThat(s.tag()).isNull();
			assertThat(s.customTitle()).isNull();
			assertThat(s.aiTitle()).isNull();
			assertThat(s.displayTitle()).isNull();
		}

		@Test
		void lastLineWins(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID, tagLine("first"), tagLine("second"));
			assertThat(load(dir, false).tag()).isEqualTo("second");
		}

		@Test
		void emptyTagLineClears(@TempDir Path dir) throws IOException {
			// The CLI represents "tag removed" as a trailing {"tag":""} line.
			writeTranscript(dir, ID, tagLine("was-set"), tagLine(""));
			assertThat(load(dir, false).tag()).isNull();
		}

		@Test
		void displayTitleFallsBackToAiTitle(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID,
					"{\"type\":\"ai-title\",\"aiTitle\":\"Auto title\",\"sessionId\":\"" + ID + "\"}");
			assertThat(load(dir, false).displayTitle()).isEqualTo("Auto title");
		}

		@Test
		void lightweightScanSeesLabelsAndCwd(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID, tagLine("lite-group"),
					"{\"type\":\"custom-title\",\"customTitle\":\"Lite title\",\"sessionId\":\"" + ID + "\"}");
			Session s = load(dir, true);
			assertThat(s.entries()).isEmpty(); // still a lightweight session
			assertThat(s.workingDirectory()).isEqualTo("/work/proj");
			assertThat(s.tag()).isEqualTo("lite-group");
			assertThat(s.customTitle()).isEqualTo("Lite title");
		}

		@Test
		void messageMerelyContainingLabelJsonIsNotALabel(@TempDir Path dir) throws IOException {
			// A conversation line whose *nested content* carries "type":"tag" must not be
			// mistaken for a label line (the substring pre-filter hit is parse-verified).
			String decoy = "{\"type\":\"user\",\"uuid\":\"u3\",\"parentUuid\":\"u2\",\"sessionId\":\"" + ID
					+ "\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tag\",\"tag\":\"evil\"}]}}";
			writeTranscript(dir, ID, decoy);
			assertThat(load(dir, false).tag()).isNull();
			assertThat(load(dir, true).tag()).isNull();
		}

		@Test
		void markdownShowsTitleAndTag(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID, tagLine("my-group"),
					"{\"type\":\"custom-title\",\"customTitle\":\"My run\",\"sessionId\":\"" + ID + "\"}");
			String md = TranscriptDirectory.load(dir.toString()).toMarkdown();
			assertThat(md).contains("\"My run\"").contains("[my-group]");
		}

	}

	@Nested
	class Writing {

		@Test
		void setTagAppendsCliFormatLine(@TempDir Path dir) throws IOException {
			Path file = writeTranscript(dir, ID);
			load(dir, false).setTag("my-group");

			List<String> lines = Files.readAllLines(file);
			JsonNode last = MAPPER.readTree(lines.get(lines.size() - 1));
			// Exactly the CLI's own line shape: type + tag + sessionId, nothing else.
			assertThat(last.size()).isEqualTo(3);
			assertThat(last.path("type").asText()).isEqualTo("tag");
			assertThat(last.path("tag").asText()).isEqualTo("my-group");
			assertThat(last.path("sessionId").asText()).isEqualTo(ID);

			assertThat(load(dir, false).tag()).isEqualTo("my-group");
			assertThat(load(dir, true).tag()).isEqualTo("my-group");
		}

		@Test
		void setTagUpdatesInMemoryState(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID);
			Session s = load(dir, false);
			s.setTag("my-group");
			assertThat(s.tag()).isEqualTo("my-group");
			assertThat(s.labels().tag()).isEqualTo("my-group");
		}

		@Test
		void clearTagAppendsEmptyTagLine(@TempDir Path dir) throws IOException {
			Path file = writeTranscript(dir, ID, tagLine("was-set"));
			Session s = load(dir, false);
			assertThat(s.tag()).isEqualTo("was-set");

			s.clearTag();
			assertThat(s.tag()).isNull();

			List<String> lines = Files.readAllLines(file);
			JsonNode last = MAPPER.readTree(lines.get(lines.size() - 1));
			assertThat(last.path("type").asText()).isEqualTo("tag");
			assertThat(last.path("tag").asText()).isEmpty();
			assertThat(load(dir, false).tag()).isNull();
		}

		@Test
		void setCustomTitleAppendsCliFormatLine(@TempDir Path dir) throws IOException {
			Path file = writeTranscript(dir, ID);
			Session s = load(dir, false);
			s.setCustomTitle("  My run  "); // trimmed like the CLI
			assertThat(s.customTitle()).isEqualTo("My run");

			List<String> lines = Files.readAllLines(file);
			JsonNode last = MAPPER.readTree(lines.get(lines.size() - 1));
			assertThat(last.size()).isEqualTo(3);
			assertThat(last.path("type").asText()).isEqualTo("custom-title");
			assertThat(last.path("customTitle").asText()).isEqualTo("My run");
			assertThat(last.path("sessionId").asText()).isEqualTo(ID);

			assertThat(load(dir, false).customTitle()).isEqualTo("My run");
		}

		@Test
		void tagIsTrimmed(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID);
			Session s = load(dir, false);
			s.setTag("  my-group  ");
			assertThat(s.tag()).isEqualTo("my-group");
			assertThat(load(dir, false).tag()).isEqualTo("my-group");
		}

		@Test
		void rejectsBlankValues(@TempDir Path dir) throws IOException {
			writeTranscript(dir, ID);
			Session s = load(dir, false);
			assertThatThrownBy(() -> s.setTag(null)).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("clearTag");
			assertThatThrownBy(() -> s.setTag("   ")).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> s.setCustomTitle(null)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> s.setCustomTitle(" ")).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void refusesMissingOrEmptyTranscript(@TempDir Path dir) throws IOException {
			// Mirrors the CLI, which refuses to label a session whose transcript is gone or
			// empty (it would not be a resumable session).
			Path file = writeTranscript(dir, ID);
			Session s = load(dir, false);

			Files.writeString(file, "");
			assertThatThrownBy(() -> s.setTag("x")).isInstanceOf(IOException.class)
					.hasMessageContaining("non-empty");

			Files.delete(file);
			assertThatThrownBy(() -> s.setTag("x")).isInstanceOf(IOException.class);
			assertThatThrownBy(() -> s.setCustomTitle("x")).isInstanceOf(IOException.class);
		}

		@Test
		void labelsSurviveRegenerate(@TempDir Path dir, @TempDir Path dest) throws IOException {
			writeTranscript(dir, ID, tagLine("my-group"));
			TranscriptDirectory.load(dir.toString()).regenerate(dest.toString());
			assertThat(TranscriptDirectory.load(dest.toString()).byId(ID).orElseThrow().tag())
					.isEqualTo("my-group");
		}

	}

}
