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
import java.nio.file.StandardCopyOption;
import java.util.UUID;

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
 * <p>To capture a session as a portable, single-file archive instead (with metadata, and the
 * option to keep the original id on restore), see {@link SessionArchive}.
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
	 * {@code targetDir}, using the default Claude projects root (see
	 * {@link TranscriptDirectory#projectsRoot()}).
	 */
	public static Result clone(String sessionId, Path sourceDir, Path targetDir) throws IOException {
		return clone(sessionId, sourceDir, targetDir, TranscriptDirectory.projectsRoot());
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

		Path srcTranscript = Transcripts.locateTranscript(projectsRoot, srcReal, sessionId);

		if (Files.exists(targetDir) && Transcripts.isNonEmptyDir(targetDir)) {
			throw new IllegalArgumentException("targetDir must be empty or non-existent: " + targetDir);
		}
		Files.createDirectories(targetDir);
		Path targetReal = targetDir.toRealPath();
		if (targetReal.startsWith(srcReal)) {
			throw new IllegalArgumentException("targetDir must not be inside sourceDir");
		}

		// 1. Duplicate the entire working-directory tree.
		Transcripts.copyTree(sourceDir, targetDir);

		// 2. Re-home a copy of the transcript under a new session id.
		String newId = UUID.randomUUID().toString();
		Path targetProjectsDir = projectsRoot.resolve(sanitize(targetReal));
		Files.createDirectories(targetProjectsDir);
		Path targetTranscript = targetProjectsDir.resolve(newId + ".jsonl");
		Transcripts.rehomeTranscript(srcTranscript, targetTranscript, srcReal.toString(), targetReal.toString(), newId);

		// 3. Copy externalized tool-results (the <session-id>/ subdir next to the transcript), if any.
		Path srcAux = srcTranscript.getParent().resolve(sessionId);
		if (Files.isDirectory(srcAux)) {
			Transcripts.copyTree(srcAux, targetProjectsDir.resolve(newId));
		}

		// 4. Carry the SDK metadata sidecar across under the new id. The map is opaque (no paths
		// to re-home), so it is copied verbatim.
		Path srcMeta = SessionMetadata.fileFor(srcTranscript);
		if (Files.isRegularFile(srcMeta)) {
			Files.copy(srcMeta, targetProjectsDir.resolve(newId + SessionMetadata.EXTENSION),
					StandardCopyOption.REPLACE_EXISTING);
		}

		return new Result(newId, targetDir, targetTranscript);
	}

	/** Claude names a working dir's transcript folder by replacing non-alphanumerics with '-'. */
	static String sanitize(Path realPath) {
		return TranscriptDirectory.sanitize(realPath);
	}

}
