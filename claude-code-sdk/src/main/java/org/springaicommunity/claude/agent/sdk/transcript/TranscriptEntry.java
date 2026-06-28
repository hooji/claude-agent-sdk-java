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

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.springaicommunity.claude.agent.sdk.types.Message;

/**
 * One line of an on-disk session transcript, retained losslessly.
 *
 * <p>Every line from the {@code .jsonl} file becomes a {@code TranscriptEntry}, in file
 * order. The structural fields ({@code uuid}, {@code parentUuid}, {@code type}, …) are
 * lifted out for convenience, the parsed SDK {@link Message} is provided when the line is a
 * conversation message, and the complete original JSON is kept in {@link #raw()} so the
 * transcript can be regenerated JSON-equivalently with no loss.
 *
 * @param lineNo 1-based line number within the source file
 * @param uuid the message uuid, or {@code null} for session-local bookkeeping lines
 * @param parentUuid the parent message uuid (in-file message tree), or {@code null}
 * @param type the raw line type (e.g. {@code user}, {@code assistant}, {@code system},
 * {@code file-history-snapshot})
 * @param isSidechain whether this line belongs to a sub-agent sidechain
 * @param agentId the sub-agent id this line is attributed to, or {@code null}
 * @param timestamp ISO-8601 timestamp, or {@code null}
 * @param message the parsed SDK message, or {@code null} for non-conversation lines
 * @param raw the complete original JSON for this line (lossless)
 */
public record TranscriptEntry(int lineNo, String uuid, String parentUuid, String type, boolean isSidechain,
		String agentId, String timestamp, Message message, JsonNode raw) {

	/** @return true if this entry carries a uuid (the lineage-bearing entries forks copy). */
	public boolean hasUuid() {
		return uuid != null;
	}

	/** @return true if this line parsed into a typed conversation {@link Message}. */
	public boolean hasMessage() {
		return message != null;
	}

	/**
	 * Extracts the on-disk file paths this line references (from {@code filePath} /
	 * {@code filename} / {@code file_path} fields anywhere in its JSON). Useful for
	 * attachments and tool/file operations: the bytes live on disk at these paths, so you
	 * get a path without loading content into memory.
	 * @return referenced absolute file paths, in encounter order (possibly empty)
	 */
	public List<String> referencedFiles() {
		return TranscriptPaths.referencedFiles(raw);
	}
}
