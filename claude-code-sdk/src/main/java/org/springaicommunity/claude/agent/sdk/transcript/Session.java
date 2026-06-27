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
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springaicommunity.claude.agent.sdk.types.Message;
import reactor.core.publisher.Flux;

/**
 * One loaded session transcript file, mirroring its on-disk content plus the recovered fork
 * partition. A session can {@linkplain #replayMessages() replay} its own full history; the
 * directory-wide knowledge that requires (sibling forks for the {@link ForkMarker}s) is
 * precomputed at load time.
 *
 * @param sessionId the session id (the transcript filename without extension)
 * @param file the source {@code .jsonl} path
 * @param agentSession true if this is a sub-agent sidechain file ({@code agent-*.jsonl})
 * @param agentId the sub-agent id for an agent session, otherwise {@code null}
 * @param workingDirectory the directory this session ran in, recovered from the {@code cwd}
 * stamped on the transcript ({@code null} if none was recorded). Unlike the sanitized storage
 * folder name, this is the real path the user ran Claude in. It is populated even by a
 * {@linkplain TranscriptDirectory#load(Path, boolean) lightweight} load (which reads only as far
 * as the first {@code cwd}). See {@link #workingDirectoryPath()} for the {@link Path} form.
 * @param entries every line of the file, in order, retained losslessly
 * @param messages the uuid-bearing subset of {@code entries} (the lineage carrier the fork
 * partition indexes into)
 * @param segments the fork partition over {@code messages}; size 1 when the session has no
 * fork points (it is then a single segment owned by this session)
 * @param forkMarkers one precomputed {@link ForkMarker} per segment boundary (so always
 * {@code segments.size() - 1} of them), in order
 * @param metaData the SDK-managed metadata associated with this session, loaded from its
 * {@code <id>.meta} sidecar (empty when none exists). This is a <em>live, mutable</em> map: to
 * change it, go through {@link #putMetaData} / {@link #removeMetaData} (which persist the change),
 * not by mutating the returned map directly. Because it is mutable, a {@code Session} must not be
 * used as a hash-map key or set element.
 */
public record Session(String sessionId, Path file, boolean agentSession, String agentId, String workingDirectory,
		List<TranscriptEntry> entries, List<TranscriptEntry> messages, List<ForkSegment> segments,
		List<ForkMarker> forkMarkers, Map<String, Serializable> metaData) {

	/**
	 * Canonical constructor; normalizes a {@code null} {@code metaData} to a fresh empty
	 * {@link LinkedHashMap}. The map is intentionally <em>not</em> defensively copied — it is the
	 * live map that {@link #putMetaData}, {@link #removeMetaData} and {@link #writeMetaData} mutate
	 * and persist.
	 */
	public Session {
		metaData = metaData == null ? new LinkedHashMap<>() : metaData;
	}

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
	 * The {@link #workingDirectory()} as a {@link Path}.
	 * @return the working directory path, or empty if no {@code cwd} was recorded
	 */
	public Optional<Path> workingDirectoryPath() {
		return workingDirectory == null ? Optional.empty() : Optional.of(Path.of(workingDirectory));
	}

	/**
	 * The path to this session's {@code <id>.meta} metadata sidecar (next to the transcript). The
	 * file may not exist — it is written lazily, the first time metadata is persisted.
	 * @return the {@code .meta} sidecar path
	 */
	public Path metaFilePath() {
		return SessionMetadata.fileFor(file);
	}

	/**
	 * The last-modified time of this session's transcript {@code .jsonl} file.
	 * @return the transcript's last-modified instant, or {@code null} if the file does not exist
	 * @throws UncheckedIOException if the file's time cannot be read
	 */
	public Instant lastTranscriptUpdateTime() {
		return lastModified(file);
	}

	/**
	 * The last-modified time of this session's {@code .meta} sidecar.
	 * @return the sidecar's last-modified instant, or {@code null} if no metadata has been written
	 * @throws UncheckedIOException if the file's time cannot be read
	 */
	public Instant lastMetaDataUpdateTime() {
		return lastModified(metaFilePath());
	}

	/**
	 * The most recent update to either the transcript or the {@code .meta} sidecar — the natural
	 * sort key for a "most recently used" session list.
	 * @return the later of {@link #lastTranscriptUpdateTime()} and {@link #lastMetaDataUpdateTime()},
	 * ignoring whichever is {@code null}; {@code null} only if neither file exists
	 */
	public Instant lastUpdateTime() {
		Instant transcript = lastTranscriptUpdateTime();
		Instant meta = lastMetaDataUpdateTime();
		if (transcript == null) {
			return meta;
		}
		if (meta == null) {
			return transcript;
		}
		return meta.isAfter(transcript) ? meta : transcript;
	}

	/** Last-modified instant of {@code path}, or {@code null} if it does not exist. */
	private static Instant lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toInstant();
		}
		catch (NoSuchFileException e) {
			return null;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Writes this session's current {@link #metaData()} to its {@code <id>.meta} sidecar file
	 * (next to the transcript), serializing the live map as it stands. Prefer {@link #putMetaData}
	 * / {@link #removeMetaData}, which mutate and persist in one step; call this directly only
	 * after mutating the map by other means.
	 * @throws IOException if the sidecar file cannot be written
	 */
	public void writeMetaData() throws IOException {
		SessionMetadata.writeToFile(metaFilePath(), metaData);
	}

	/**
	 * Associates {@code value} with {@code key} in this session's metadata and immediately
	 * persists the change to the {@code <id>.meta} sidecar, keeping the in-memory map and the file
	 * in sync.
	 * @param key the metadata key
	 * @param value the value (must be {@link Serializable}; {@code null} stores a null value)
	 * @throws IOException if the sidecar file cannot be written
	 */
	public void putMetaData(String key, Serializable value) throws IOException {
		metaData.put(key, value);
		writeMetaData();
	}

	/**
	 * Removes {@code key} from this session's metadata and immediately persists the change to the
	 * {@code <id>.meta} sidecar, keeping the in-memory map and the file in sync.
	 * @param key the metadata key to remove
	 * @throws IOException if the sidecar file cannot be written
	 */
	public void removeMetaData(String key) throws IOException {
		metaData.remove(key);
		writeMetaData();
	}

	/**
	 * Archives this session — its transcript, its {@code <id>.meta} metadata, and its entire
	 * working directory tree — to {@code targetArchive} as a single portable file (see
	 * {@link SessionArchive}). The working directory comes from {@link #workingDirectory()} and the
	 * projects root from this session's {@link #file()}; the metadata is taken from the
	 * {@code .meta} file on disk.
	 *
	 * <p>As a safety check against forgetting to persist a mutation, this verifies the in-memory
	 * {@link #metaData()} still matches the on-disk {@code .meta} (same entries, same order) and
	 * throws if they have diverged — mutate via {@link #putMetaData}/{@link #removeMetaData}, or
	 * call {@link #writeMetaData()} before archiving.
	 * @param targetArchive the archive file to write
	 * @return the archive file written
	 * @throws IOException if the session's files can't be read or the archive can't be written
	 * @throws IllegalStateException if the working directory can't be inferred (no {@code cwd} in
	 * the transcript), or the in-memory metadata differs from the on-disk {@code .meta}
	 */
	public Path archiveTo(Path targetArchive) throws IOException {
		Path workingDir = workingDirectoryPath().orElseThrow(() -> new IllegalStateException(
				"Cannot infer the working directory for session " + sessionId
						+ " (no cwd recorded in its transcript); use SessionArchive.create(sessionId, "
						+ "workingDir, ...) with an explicit directory"));
		Path folder = file.getParent();
		Path projectsRoot = folder == null ? null : folder.getParent();
		if (projectsRoot == null) {
			throw new IllegalStateException("Cannot derive the projects root from transcript path " + file);
		}
		Map<String, Serializable> onDisk = SessionMetadata.readFromFile(metaFilePath());
		if (!SessionMetadata.equalsOrdered(metaData, onDisk)) {
			throw new IllegalStateException("In-memory metadata for session " + sessionId
					+ " differs from its on-disk .meta file; mutate via putMetaData/removeMetaData, or call "
					+ "writeMetaData() before archiving");
		}
		return SessionArchive.create(sessionId, workingDir, targetArchive, projectsRoot);
	}

	/**
	 * Replays this session's full history (root through this leaf) as SDK
	 * {@link Message}s, in a form compatible with live message handling. <b>Every</b>
	 * transcript line is emitted, in file order: conversation lines as their parsed
	 * {@link Message} type, and all other lines (e.g. {@code attachment},
	 * {@code queue-operation}, {@code mode}) as a {@link RawTranscriptMessage} carrying the
	 * raw type and JSON — so the consumer can choose to surface or hide each. A
	 * {@link ForkMarker} is emitted at each fork boundary and a terminal {@link HistoryEnd}
	 * signals completion. Unlike {@link #messages()}, which is the raw uuid-bearing entry
	 * list, this view interleaves those synthetic marker messages.
	 * @return the ordered replay messages
	 */
	public List<Message> replayMessages() {
		List<Message> out = new ArrayList<>();
		int uuidPos = 0; // position within the uuid-bearing message list (the partition coordinate)
		int seg = 0;
		for (TranscriptEntry e : entries) {
			if (e.hasUuid()) {
				// Crossing into a later segment: emit its fork marker before this message.
				while (seg + 1 < segments.size() && uuidPos >= segments.get(seg + 1).startIndex()) {
					seg++;
					out.add(forkMarkers.get(seg - 1));
				}
				uuidPos++;
			}
			// Emit EVERY line: parsed conversation message, or a raw passthrough otherwise.
			out.add(e.hasMessage() ? e.message() : new RawTranscriptMessage(e.type(), e.uuid(), e.raw()));
		}
		out.add(new HistoryEnd(sessionId, messages.size()));
		return out;
	}

	/**
	 * Reactive form of {@link #replayMessages()}.
	 * @return the replayed messages as a {@link Flux} (cold; emits on subscribe)
	 */
	public Flux<Message> replay() {
		return Flux.fromIterable(replayMessages());
	}
}
