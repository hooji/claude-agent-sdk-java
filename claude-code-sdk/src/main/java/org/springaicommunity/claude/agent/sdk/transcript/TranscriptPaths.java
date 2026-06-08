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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Extracts on-disk file paths referenced anywhere within a transcript line's JSON. Claude
 * Code externalizes file content rather than inlining it — edited/referenced files appear as
 * absolute paths under {@code filePath} / {@code filename} / {@code file_path} fields (in
 * {@code attachment} lines, tool results, and file operations) — so the bytes can be read
 * straight from disk without loading them into memory.
 */
final class TranscriptPaths {

	private static final Set<String> PATH_FIELDS = Set.of("filePath", "filename", "file_path");

	private TranscriptPaths() {
	}

	static List<Path> referencedFiles(JsonNode raw) {
		LinkedHashSet<Path> out = new LinkedHashSet<>();
		collect(raw, out);
		return List.copyOf(out);
	}

	private static void collect(JsonNode node, LinkedHashSet<Path> out) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			var fields = node.fields();
			while (fields.hasNext()) {
				var entry = fields.next();
				JsonNode value = entry.getValue();
				if (value.isTextual() && PATH_FIELDS.contains(entry.getKey()) && looksLikePath(value.asText())) {
					out.add(Path.of(value.asText()));
				}
				else {
					collect(value, out);
				}
			}
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				collect(child, out);
			}
		}
	}

	private static boolean looksLikePath(String s) {
		return s != null && !s.isBlank()
				&& (s.startsWith("/") || s.startsWith("~") || s.matches("^[A-Za-z]:[\\\\/].*"));
	}
}
