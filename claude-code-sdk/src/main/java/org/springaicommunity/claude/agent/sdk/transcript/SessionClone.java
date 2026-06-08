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
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Clones a Claude Code session into a new working directory, keeping the conversation
 * <em>and</em> the file state in sync — unlike the built-in {@code --fork-session}, which
 * branches the conversation but leaves a single shared working directory (so a fork's
 * conversation can become inconsistent with the files on disk).
 *
 * <p>A clone is more like a UTM VM snapshot copy: it duplicates the session's entire working
 * directory tree into a fresh directory and re-homes a copy of the conversation transcript
 * (new session id, paths rewritten from the source to the target directory) so the new
 * session resumes against its own files. The original and the clone then move forward on
 * independent timelines without affecting each other.
 *
 * <p><b>Important:</b> for this to remain consistent, clones must be created through this API
 * and the resulting session resumed in its target directory — not forked again via the CLI.
 */
public final class SessionClone {

	private SessionClone() {
	}

	/**
	 * The result of a clone.
	 *
	 * @param sessionId the new (clone) session id, to resume against {@link #workingDirectory()}
	 * @param workingDirectory the clone's working directory (a copy of the source tree)
	 * @param transcriptPath the re-homed transcript file
	 */
	public record Result(String sessionId, Path workingDirectory, Path transcriptPath) {
	}

	/**
	 * Clones {@code sessionId} (whose working directory is {@code sourceDir}) into
	 * {@code targetDir}, using the default Claude projects root ({@code ~/.claude/projects}).
	 */
	public static Result clone(String sessionId, Path sourceDir, Path targetDir) throws IOException {
		Path projectsRoot = Path.of(System.getProperty("user.home"), ".claude", "projects");
		return clone(sessionId, sourceDir, targetDir, projectsRoot);
	}

	/**
	 * Clones {@code sessionId} into {@code targetDir}, with an explicit {@code projectsRoot}
	 * (the directory that holds the per-working-directory transcript folders).
	 * @return the new session's id, working directory, and transcript path
	 * @throws IllegalArgumentException if the source session/transcript can't be found, the
	 * sanitization scheme doesn't match, or the target is unusable
	 */
	public static Result clone(String sessionId, Path sourceDir, Path targetDir, Path projectsRoot) throws IOException {
		if (!Files.isDirectory(sourceDir)) {
			throw new IllegalArgumentException("sourceDir is not a directory: " + sourceDir);
		}
		Path srcReal = sourceDir.toRealPath();

		Path srcTranscript = locateTranscript(projectsRoot, srcReal, sessionId);

		if (Files.exists(targetDir) && isNonEmptyDir(targetDir)) {
			throw new IllegalArgumentException("targetDir must be empty or non-existent: " + targetDir);
		}
		Files.createDirectories(targetDir);
		Path targetReal = targetDir.toRealPath();
		if (targetReal.startsWith(srcReal)) {
			throw new IllegalArgumentException("targetDir must not be inside sourceDir");
		}

		// 1. Duplicate the entire working-directory tree.
		copyTree(sourceDir, targetDir);

		// 2. Re-home a copy of the transcript under a new session id.
		String newId = UUID.randomUUID().toString();
		Path targetProjectsDir = projectsRoot.resolve(sanitize(targetReal));
		Files.createDirectories(targetProjectsDir);
		Path targetTranscript = targetProjectsDir.resolve(newId + ".jsonl");
		rehomeTranscript(srcTranscript, targetTranscript, srcReal.toString(), targetReal.toString(), newId);

		// 3. Copy externalized tool-results (the <session-id>/ subdir next to the transcript), if any.
		Path srcAux = srcTranscript.getParent().resolve(sessionId);
		if (Files.isDirectory(srcAux)) {
			copyTree(srcAux, targetProjectsDir.resolve(newId));
		}

		return new Result(newId, targetDir, targetTranscript);
	}

	/** Finds the source transcript and verifies our path-sanitization matches Claude's. */
	private static Path locateTranscript(Path projectsRoot, Path srcReal, String sessionId) throws IOException {
		Path expected = projectsRoot.resolve(sanitize(srcReal)).resolve(sessionId + ".jsonl");
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
							+ " — the path-sanitization scheme does not match; aborting to avoid writing the clone "
							+ "to the wrong location.");
				}
			}
		}
		throw new IllegalArgumentException(
				"No transcript for session " + sessionId + " under " + projectsRoot + " (expected " + expected + ")");
	}

	/** Claude names a working dir's transcript folder by replacing '/' and '.' with '-'. */
	static String sanitize(Path realPath) {
		return realPath.toString().replaceAll("[/.]", "-");
	}

	private static boolean isNonEmptyDir(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.findAny().isPresent();
		}
	}

	private static void copyTree(Path source, Path target) throws IOException {
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

	private static void rehomeTranscript(Path src, Path dst, String fromPath, String toPath, String newSessionId)
			throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		List<String> out = new ArrayList<>();
		for (String line : Files.readAllLines(src)) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node;
			try {
				node = mapper.readTree(line);
			}
			catch (Exception e) {
				out.add(line); // keep verbatim if it isn't JSON
				continue;
			}
			rehome(node, fromPath, toPath);
			if (node instanceof ObjectNode obj && obj.has("sessionId")) {
				obj.put("sessionId", newSessionId);
			}
			out.add(mapper.writeValueAsString(node));
		}
		Files.write(dst, out);
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
