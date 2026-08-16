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

package org.springaicommunity.claude.agent.sdk.transcript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A session's <em>official</em> Claude Code labels — the tag and titles the CLI itself
 * reads and writes, as opposed to the SDK-private {@code .meta} sidecar
 * ({@link Session#metaData()}).
 *
 * <p>
 * Claude Code stores these as dedicated bookkeeping lines <em>appended to the session's
 * transcript {@code .jsonl}</em>, one JSON object per write, latest line wins:
 *
 * <pre>{@code
 * {"type":"tag","tag":"my-group","sessionId":"<id>"}
 * {"type":"custom-title","customTitle":"My run","sessionId":"<id>"}
 * {"type":"ai-title","aiTitle":"Fixing the build","sessionId":"<id>"}
 * }</pre>
 *
 * <ul>
 * <li><b>{@code tag}</b> — a single free-form string per session. This is what the Claude
 * Code desktop app calls a <b>custom group</b> (sidebar "Group by → Custom groups") and
 * what groups sessions in the CLI's {@code /resume} picker; the official Agent SDK
 * exposes it as {@code tagSession(sessionId, tag)}. An empty-string tag line clears the
 * tag.</li>
 * <li><b>{@code customTitle}</b> — the user-set session title (the CLI's {@code /rename},
 * the picker's rename action; {@code renameSession} in the official Agent SDK).</li>
 * <li><b>{@code aiTitle}</b> — the automatically generated session title. Written by the
 * CLI only; the SDK reads it but never writes it.</li>
 * </ul>
 *
 * <p>
 * Instances are live holders owned by a {@link Session}: loading populates them
 * (last-wins over the transcript), and {@link Session#setTag}, {@link Session#clearTag}
 * and {@link Session#setCustomTitle} persist a new line and update the holder in one
 * step.
 */
public final class SessionLabels {

	/** Line type of a tag label ({@code {"type":"tag","tag":...}}). */
	static final String TYPE_TAG = "tag";

	/**
	 * Line type of a user-set title label
	 * ({@code {"type":"custom-title","customTitle":...}}).
	 */
	static final String TYPE_CUSTOM_TITLE = "custom-title";

	/**
	 * Line type of a generated title label ({@code {"type":"ai-title","aiTitle":...}}).
	 */
	static final String TYPE_AI_TITLE = "ai-title";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private String tag;

	private String customTitle;

	private String aiTitle;

	/**
	 * The session's tag — its custom-group name in the Claude Code desktop app.
	 * @return the tag, or {@code null} when the session has none (never empty)
	 */
	public String tag() {
		return tag;
	}

	/**
	 * The user-set session title (set via the CLI's {@code /rename} or
	 * {@link Session#setCustomTitle}).
	 * @return the custom title, or {@code null} when none was set
	 */
	public String customTitle() {
		return customTitle;
	}

	/**
	 * The automatically generated session title (written by the CLI as the conversation
	 * develops).
	 * @return the generated title, or {@code null} when none was recorded
	 */
	public String aiTitle() {
		return aiTitle;
	}

	/**
	 * Updates the in-memory tag ({@code null} = cleared); persistence is the caller's
	 * job.
	 */
	void tagValue(String tag) {
		this.tag = emptyToNull(tag);
	}

	/** Updates the in-memory custom title; persistence is the caller's job. */
	void customTitleValue(String customTitle) {
		this.customTitle = emptyToNull(customTitle);
	}

	/**
	 * Applies one transcript line to this holder if it is a label line; empty values
	 * clear (the CLI writes {@code "tag":""} to remove a tag). Call in file order so the
	 * last occurrence wins, matching the CLI's read semantics.
	 * @param type the line's {@code type} field ({@code null} tolerated)
	 * @param node the line's JSON
	 */
	void apply(String type, JsonNode node) {
		if (type == null) {
			return;
		}
		switch (type) {
			case TYPE_TAG -> this.tag = emptyToNull(node.path("tag").asText(null));
			case TYPE_CUSTOM_TITLE -> this.customTitle = emptyToNull(node.path("customTitle").asText(null));
			case TYPE_AI_TITLE -> this.aiTitle = emptyToNull(node.path("aiTitle").asText(null));
			default -> {
			}
		}
	}

	/**
	 * Scans a raw transcript line cheaply — substring pre-filter before any JSON parsing,
	 * the same trick the CLI uses — and applies it if it is a label line. For the
	 * lightweight (metadata-only) load path.
	 */
	void applyRawLine(String line, ObjectMapper mapper) {
		// The CLI writes compact JSON, so the quoted type is a reliable pre-filter; a hit
		// is still verified by parsing (a conversation message merely *containing* the
		// text parses to a different type and is ignored by apply()).
		if (!line.contains("\"type\":\"" + TYPE_TAG + "\"") && !line.contains("\"type\":\"" + TYPE_CUSTOM_TITLE + "\"")
				&& !line.contains("\"type\":\"" + TYPE_AI_TITLE + "\"")) {
			return;
		}
		try {
			JsonNode node = mapper.readTree(line);
			if (node.isObject()) {
				apply(node.path("type").asText(null), node);
			}
		}
		catch (Exception ignored) {
			// not JSON — skip
		}
	}

	/** Last-wins extraction over a fully parsed session's entries. */
	static SessionLabels fromEntries(List<TranscriptEntry> entries) {
		SessionLabels labels = new SessionLabels();
		for (TranscriptEntry e : entries) {
			labels.apply(e.type(), e.raw());
		}
		return labels;
	}

	// ============================================================
	// Write side — appending label lines the way the CLI does
	// ============================================================

	/**
	 * Appends a tag line to {@code transcriptFile}, exactly as the CLI's
	 * {@code tagSession} does: {@code {"type":"tag","tag":"<tag>","sessionId":"<id>"}}
	 * with an empty string meaning "clear". The transcript must already exist and be
	 * non-empty (the CLI refuses to label a session that has no history — an empty file
	 * would not be a resumable session).
	 * @param transcriptFile the session's {@code .jsonl}
	 * @param sessionId the session id stamped on the line
	 * @param tag the trimmed tag, or {@code null} to clear
	 * @throws IOException if the transcript is missing/empty or the append fails
	 */
	static void appendTagLine(String transcriptFile, String sessionId, String tag) throws IOException {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("type", TYPE_TAG);
		node.put("tag", tag == null ? "" : tag);
		node.put("sessionId", sessionId);
		appendLine(transcriptFile, sessionId, node);
	}

	/**
	 * Appends a custom-title line to {@code transcriptFile}, exactly as the CLI's
	 * {@code renameSession} does:
	 * {@code {"type":"custom-title","customTitle":"<title>","sessionId":"<id>"}}.
	 * @param transcriptFile the session's {@code .jsonl}
	 * @param sessionId the session id stamped on the line
	 * @param title the trimmed, non-empty title
	 * @throws IOException if the transcript is missing/empty or the append fails
	 */
	static void appendCustomTitleLine(String transcriptFile, String sessionId, String title) throws IOException {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("type", TYPE_CUSTOM_TITLE);
		node.put("customTitle", title);
		node.put("sessionId", sessionId);
		appendLine(transcriptFile, sessionId, node);
	}

	private static void appendLine(String transcriptFile, String sessionId, ObjectNode node) throws IOException {
		Path path = Path.of(transcriptFile);
		if (!Files.isRegularFile(path) || Files.size(path) == 0) {
			throw new IOException("Session " + sessionId + " has no transcript to label at " + transcriptFile
					+ " (the file must exist and be non-empty)");
		}
		String line = MAPPER.writeValueAsString(node) + "\n";
		Files.writeString(path, line, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
	}

	private static String emptyToNull(String s) {
		return s == null || s.isEmpty() ? null : s;
	}

	@Override
	public String toString() {
		return "SessionLabels[tag=" + tag + ", customTitle=" + customTitle + ", aiTitle=" + aiTitle + "]";
	}

}
