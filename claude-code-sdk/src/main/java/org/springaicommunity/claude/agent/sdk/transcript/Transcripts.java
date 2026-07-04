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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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

	/**
	 * The name of the AI's persistent-memory folder that Claude Code keeps <em>next to the
	 * transcripts</em> in a working directory's projects folder (i.e.
	 * {@code <projectsRoot>/<sanitized-workdir>/memory/}) — the files the memory tool writes
	 * ({@code MEMORY.md} and its topic files). Shared by every session that runs in that working
	 * directory.
	 */
	static final String MEMORY_DIR = "memory";

	/**
	 * The name of the folder holding per-session task lists (the TODO tool's records), a
	 * <em>sibling of the projects root</em> under the Claude config dir: each session's tasks
	 * live in {@code <configDir>/tasks/<sessionId>/} as one JSON file per task (plus a
	 * {@code .lock}). Unlike {@link #MEMORY_DIR}, which is per working directory, this is keyed
	 * by session id.
	 */
	static final String TASKS_DIR = "tasks";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private Transcripts() {
	}

	/**
	 * The task-list folder for {@code sessionId}, derived from {@code projectsRoot} (the tasks
	 * root is the projects root's sibling {@code tasks/} folder — verified against the CLI:
	 * {@code <configDir>/projects/...} and {@code <configDir>/tasks/<sessionId>/}).
	 * @return the folder (which may not exist), or {@code null} if {@code projectsRoot} has no
	 * parent to hang the tasks root off
	 */
	static Path tasksDirFor(String projectsRoot, String sessionId) {
		Path parent = Path.of(projectsRoot).toAbsolutePath().normalize().getParent();
		return parent == null ? null : parent.resolve(TASKS_DIR).resolve(sessionId);
	}

	/**
	 * Whether {@code p} is the CLI's {@code .lock} file (kept in a session's tasks folder).
	 * It mirrors the live app's internal locking state, so it is never carried into an archive,
	 * clone, or restore — a new session must start with its own lock state, or it might refuse
	 * to update the task list.
	 */
	static boolean isLockFile(Path p) {
		Path name = p.getFileName();
		return name != null && name.toString().equals(".lock");
	}

	/** Whether {@code dir} holds any actual task records ({@code .lock} alone doesn't count). */
	static boolean hasTaskRecords(Path dir) throws IOException {
		if (dir == null || !Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.anyMatch(p -> Files.isRegularFile(p) && !isLockFile(p));
		}
	}

	/** Recursively copies the file tree rooted at {@code source} into {@code target}. */
	static void copyTree(String source, String target) throws IOException {
		Path sourceRoot = Path.of(source);
		Path targetRoot = Path.of(target);
		try (Stream<Path> walk = Files.walk(sourceRoot)) {
			walk.forEach(src -> {
				Path dst = targetRoot.resolve(sourceRoot.relativize(src).toString());
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

	/**
	 * As {@link #copyTree} but re-homing each file's content: every occurrence of
	 * {@code fromPath} in a text file is rewritten to {@code toPath} (see
	 * {@link #rehomeFileBytes}). Used for the memory and tasks folders, whose free-form files
	 * may reference absolute paths of the working directory they were written in.
	 */
	static void copyTreeRehoming(String source, String target, String fromPath, String toPath) throws IOException {
		copyTreeRehoming(source, target, fromPath, toPath, p -> true);
	}

	/** As {@link #copyTreeRehoming} but copying only the entries {@code include} accepts. */
	static void copyTreeRehoming(String source, String target, String fromPath, String toPath,
			Predicate<Path> include) throws IOException {
		Path sourceRoot = Path.of(source);
		Path targetRoot = Path.of(target);
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(sourceRoot)) {
			paths = walk.sorted().toList();
		}
		for (Path src : paths) {
			if (!include.test(src)) {
				continue;
			}
			Path dst = targetRoot.resolve(sourceRoot.relativize(src).toString());
			if (Files.isDirectory(src)) {
				Files.createDirectories(dst);
			}
			else {
				Files.createDirectories(dst.getParent());
				Files.write(dst, rehomeFileBytes(Files.readAllBytes(src), fromPath, toPath));
			}
		}
	}

	/**
	 * Re-homes a single file's bytes: rewrites every occurrence of {@code fromPath} to
	 * {@code toPath} when the bytes are valid UTF-8 text; bytes that don't decode as UTF-8 (a
	 * binary file) are returned unchanged rather than risk corruption.
	 */
	static byte[] rehomeFileBytes(byte[] bytes, String fromPath, String toPath) {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT);
		String text;
		try {
			text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException e) {
			return bytes;
		}
		if (!text.contains(fromPath)) {
			return bytes;
		}
		return text.replace(fromPath, toPath).getBytes(StandardCharsets.UTF_8);
	}

	/** @return whether {@code dir} exists, is a directory, and contains at least one entry. */
	static boolean isNonEmptyDir(String dir) throws IOException {
		Path dirPath = Path.of(dir);
		if (!Files.isDirectory(dirPath)) {
			return false;
		}
		try (Stream<Path> s = Files.list(dirPath)) {
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
	static String locateTranscript(String projectsRoot, String srcReal, String sessionId) throws IOException {
		Path projectsRootPath = Path.of(projectsRoot);
		Path expected = projectsRootPath.resolve(TranscriptDirectory.sanitize(srcReal)).resolve(sessionId + ".jsonl");
		if (Files.isRegularFile(expected)) {
			return expected.toString();
		}
		// Fallback: search, so we can give a precise error if the sanitization scheme differs.
		if (Files.isDirectory(projectsRootPath)) {
			try (Stream<Path> dirs = Files.list(projectsRootPath)) {
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
	static void rehomeTranscript(String src, String dst, String fromPath, String toPath, String newSessionId)
			throws IOException {
		Files.write(Path.of(dst), rehomeLines(Files.readAllLines(Path.of(src)), fromPath, toPath, newSessionId));
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
