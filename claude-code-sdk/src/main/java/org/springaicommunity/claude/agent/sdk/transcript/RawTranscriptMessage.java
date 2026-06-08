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

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.springaicommunity.claude.agent.sdk.types.Message;

/**
 * A passthrough {@link Message} wrapping a transcript line that is not a conversation message
 * (for example {@code attachment}, {@code queue-operation}, {@code mode}, {@code last-prompt},
 * {@code file-history-snapshot}).
 *
 * <p>{@link TranscriptDirectory#replay(String)} emits one of these for every non-conversation
 * line so that nothing is dropped — the consumer gets the line's original {@link #getType()}
 * and full {@link #raw()} JSON and can choose to surface or hide each event in its UI.
 *
 * @param rawType the line's original {@code type} (the value returned by {@link #getType()})
 * @param uuid the line's uuid, or {@code null}
 * @param raw the complete original JSON for the line
 */
public record RawTranscriptMessage(String rawType, String uuid, JsonNode raw) implements Message {

	@Override
	public String getType() {
		return rawType != null ? rawType : "unknown";
	}

	/**
	 * Extracts the on-disk file paths this line references (e.g. an attachment's edited
	 * file). The bytes live on disk at these paths — read them only if/when needed.
	 * @return referenced absolute file paths (possibly empty)
	 */
	public List<Path> referencedFiles() {
		return TranscriptPaths.referencedFiles(raw);
	}

	@Override
	public String toString() {
		return "[" + getType() + (uuid != null ? " " + uuid.substring(0, Math.min(8, uuid.length())) : "") + "]";
	}
}
