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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One loaded session transcript file, mirroring its on-disk content plus the recovered fork
 * partition.
 *
 * @param sessionId the session id (the transcript filename without extension)
 * @param file the source {@code .jsonl} path
 * @param agentSession true if this is a sub-agent sidechain file ({@code agent-*.jsonl})
 * @param agentId the sub-agent id for an agent session, otherwise {@code null}
 * @param entries every line of the file, in order, retained losslessly
 * @param messages the uuid-bearing subset of {@code entries} (the lineage carrier the fork
 * partition indexes into)
 * @param segments the fork partition over {@code messages}; size 1 when the session has no
 * fork points (it is then a single segment owned by this session)
 */
public record Session(String sessionId, Path file, boolean agentSession, String agentId,
		List<TranscriptEntry> entries, List<TranscriptEntry> messages, List<ForkSegment> segments) {

	/** @return true if this session inherited history from a fork (more than one segment). */
	public boolean isFork() {
		return segments.size() > 1;
	}

	/** @return the originating session id of the conversation root (first segment). */
	public String rootSessionId() {
		return segments.isEmpty() ? sessionId : segments.get(0).originSessionId();
	}

	/**
	 * @return the session this one was forked from, or {@code null} if it is a root. The
	 * parent is the origin of the second-to-last segment (its own messages are the last
	 * segment).
	 */
	public String parentSessionId() {
		return segments.size() < 2 ? null : segments.get(segments.size() - 2).originSessionId();
	}

	/**
	 * @return the index in this session's message list at which it diverged from its parent
	 * (the start of its own final segment), or -1 if it is a root. Because {@code
	 * --fork-session} forks from the parent's latest state, this equals the parent's message
	 * count.
	 */
	public int forkPointIndex() {
		return isFork() ? segments.get(segments.size() - 1).startIndex() : -1;
	}

	/**
	 * The working directory this session ran in, recovered from the {@code cwd} stamped on its
	 * transcript entries. Unlike the storage folder name — which sanitizes the path
	 * irreversibly — this is the real directory the user ran Claude in.
	 * @return the working directory, or empty if no {@code cwd} was recorded
	 */
	public Optional<Path> workingDirectory() {
		for (TranscriptEntry e : entries) {
			JsonNode cwd = e.raw().get("cwd");
			if (cwd != null && cwd.isTextual() && !cwd.asText().isBlank()) {
				return Optional.of(Path.of(cwd.asText()));
			}
		}
		return Optional.empty();
	}

	/**
	 * Archives this session — its transcript and its entire working directory tree — to
	 * {@code targetArchive} as a single portable file (see {@link SessionArchive}). The working
	 * directory is recovered via {@link #workingDirectory()} and the projects root from this
	 * session's {@link #file()}.
	 * @param targetArchive the archive file to write
	 * @param metadata name/description/attributes to embed (may be {@code null})
	 * @return the archive file written
	 * @throws IOException if the session's files can't be read or the archive can't be written
	 * @throws IllegalStateException if the working directory can't be inferred (no {@code cwd} in
	 * the transcript) — call {@link SessionArchive#create} with an explicit directory instead
	 */
	public Path archiveTo(Path targetArchive, SessionArchive.Metadata metadata) throws IOException {
		Path workingDir = workingDirectory().orElseThrow(() -> new IllegalStateException(
				"Cannot infer the working directory for session " + sessionId
						+ " (no cwd recorded in its transcript); use SessionArchive.create(sessionId, "
						+ "workingDir, ...) with an explicit directory"));
		Path folder = file.getParent();
		Path projectsRoot = folder == null ? null : folder.getParent();
		if (projectsRoot == null) {
			throw new IllegalStateException("Cannot derive the projects root from transcript path " + file);
		}
		return SessionArchive.create(sessionId, workingDir, targetArchive, metadata, projectsRoot);
	}
}
