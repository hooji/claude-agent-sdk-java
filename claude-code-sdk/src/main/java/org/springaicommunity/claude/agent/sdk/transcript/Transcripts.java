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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Shared low-level helpers for duplicating a Claude Code working tree and re-homing a
 * transcript (rewriting absolute path references from one working directory to another and
 * re-stamping the {@code sessionId}). Used by both {@link SessionClone} (fork to a new
 * directory under a fresh id) and {@link SessionArchive} (restore an archived session into a
 * new directory, keeping or replacing the id) so the two cannot drift apart.
 */
final class Transcripts {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private Transcripts() {
	}

	/** Recursively copies the file tree rooted at {@code source} into {@code target}. */
	static void copyTree(Path source, Path target) throws IOException {
		try (Stream<Path> walk = Files.walk(source)) {
			walk.forEach(src -> {
				Path dst = target.resolve(source.relativize(src).toString());
				try {
					if (Files.isDirectory(src)) {
						Files.createDirectories(dst);
					}
					else {
						Files.createDirectories(dst.getParent());
						Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
					}
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		}
	}

	/** @return whether {@code dir} exists, is a directory, and contains at least one entry. */
	static boolean isNonEmptyDir(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.findAny().isPresent();
		}
	}

	/**
	 * Finds the transcript for {@code sessionId} whose working directory canonicalizes to
	 * {@code srcReal}, verifying our path-sanitization matches Claude's so we never read (or
	 * write) the wrong location.
	 * @throws IllegalArgumentException if the transcript can't be found, or is found at a
	 * location our sanitization scheme would not have predicted
	 */
	static Path locateTranscript(Path projectsRoot, Path srcReal, String sessionId) throws IOException {
		Path expected = projectsRoot.resolve(TranscriptDirectory.sanitize(srcReal)).resolve(sessionId + ".jsonl");
		if (Files.isRegularFile(expected)) {
			return expected;
		}
		// Fallback: search, so we can give a precise error if the sanitization scheme differs.
		if (Files.isDirectory(projectsRoot)) {
			try (Stream<Path> dirs = Files.list(projectsRoot)) {
				Path found = dirs.map(d -> d.resolve(sessionId + ".jsonl"))
						.filter(Files::isRegularFile)
						.findFirst()
						.orElse(null);
				if (found != null) {
					throw new IllegalArgumentException("Found the source transcript at " + found.getParent()
							+ " but expected " + expected.getParent()
							+ " — the path-sanitization scheme does not match; aborting to avoid using the wrong "
							+ "location.");
				}
			}
		}
		throw new IllegalArgumentException(
				"No transcript for session " + sessionId + " under " + projectsRoot + " (expected " + expected + ")");
	}

	/** Reads {@code src}, re-homes every line, and writes the result to {@code dst}. */
	static void rehomeTranscript(Path src, Path dst, String fromPath, String toPath, String newSessionId)
			throws IOException {
		Files.write(dst, rehomeLines(Files.readAllLines(src), fromPath, toPath, newSessionId));
	}

	/**
	 * Re-homes transcript lines: rewrites every string value containing {@code fromPath} to use
	 * {@code toPath}, and stamps {@code newSessionId} onto each line's {@code sessionId} field.
	 * Blank lines are dropped and non-JSON lines kept verbatim. Each message's {@code uuid} /
	 * {@code parentUuid} are intentionally left unchanged (matching {@code --fork-session}).
	 */
	static List<String> rehomeLines(List<String> lines, String fromPath, String toPath, String newSessionId)
			throws IOException {
		List<String> out = new ArrayList<>(lines.size());
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node;
			try {
				node = MAPPER.readTree(line);
			}
			catch (Exception e) {
				out.add(line); // keep verbatim if it isn't JSON
				continue;
			}
			rehome(node, fromPath, toPath);
			if (node instanceof ObjectNode obj && obj.has("sessionId")) {
				obj.put("sessionId", newSessionId);
			}
			out.add(MAPPER.writeValueAsString(node));
		}
		return out;
	}

	/** Recursively rewrites every string value that contains {@code from} to use {@code to}. */
	private static void rehome(JsonNode node, String from, String to) {
		if (node instanceof ObjectNode obj) {
			List<String> names = new ArrayList<>();
			obj.fieldNames().forEachRemaining(names::add);
			for (String n : names) {
				JsonNode v = obj.get(n);
				if (v.isTextual()) {
					String s = v.asText();
					if (s.contains(from)) {
						obj.put(n, s.replace(from, to));
					}
				}
				else {
					rehome(v, from, to);
				}
			}
		}
		else if (node instanceof ArrayNode arr) {
			for (int i = 0; i < arr.size(); i++) {
				JsonNode v = arr.get(i);
				if (v.isTextual()) {
					String s = v.asText();
					if (s.contains(from)) {
						arr.set(i, TextNode.valueOf(s.replace(from, to)));
					}
				}
				else {
					rehome(v, from, to);
				}
			}
		}
	}

}
