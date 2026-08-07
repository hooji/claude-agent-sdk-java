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
 * {@linkplain TranscriptDirectory#load(String, boolean) lightweight} load.
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
 * @param labels the session's official Claude Code labels — {@linkplain #tag() tag} (the desktop
 * app's "custom group"), {@linkplain #customTitle() custom title} and {@linkplain #aiTitle()
 * generated title} — recovered from the transcript's label lines (see {@link SessionLabels}).
 * Like {@code metaData}, this is a live holder: change it through {@link #setTag},
 * {@link #clearTag} and {@link #setCustomTitle}, which persist the change to the transcript.
 * Populated by both full and lightweight loads.
 */
public record Session(String sessionId, String file, boolean agentSession, String agentId, String workingDirectory,
		List<TranscriptEntry> entries, List<TranscriptEntry> messages, List<ForkSegment> segments,
		List<ForkMarker> forkMarkers, Map<String, Serializable> metaData, SessionLabels labels) {

	/**
	 * Canonical constructor; normalizes a {@code null} {@code metaData} to a fresh empty
	 * {@link LinkedHashMap} and a {@code null} {@code labels} to an empty holder. Neither is
	 * defensively copied — they are the live containers the mutators
	 * ({@link #putMetaData}/{@link #removeMetaData}, {@link #setTag}/{@link #setCustomTitle})
	 * mutate and persist.
	 */
	public Session {
		metaData = metaData == null ? new LinkedHashMap<>() : metaData;
		labels = labels == null ? new SessionLabels() : labels;
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
	 * The path to this session's {@code <id>.meta} metadata sidecar (next to the transcript). The
	 * file may not exist — it is written lazily, the first time metadata is persisted.
	 * @return the {@code .meta} sidecar path
	 */
	public String metaFilePath() {
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
	private static Instant lastModified(String path) {
		try {
			return Files.getLastModifiedTime(Path.of(path)).toInstant();
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

	// ============================================================
	// Official Claude Code labels (tag / titles) — see SessionLabels
	// ============================================================

	/**
	 * The session's tag — the single free-form label Claude Code itself associates with a
	 * session. This is the value behind the desktop app's <b>custom groups</b> (sidebar
	 * "Group by → Custom groups") and the session grouping in the CLI's {@code /resume}
	 * picker; the official Agent SDK reads and writes it as {@code tagSession}. Distinct
	 * from the SDK-private {@link #metaData()} sidecar, which Claude Code never sees.
	 * @return the tag (custom-group name), or {@code null} when the session has none
	 */
	public String tag() {
		return labels.tag();
	}

	/**
	 * The user-set session title (the CLI's {@code /rename}; {@code renameSession} in the
	 * official Agent SDK).
	 * @return the custom title, or {@code null} when none was set
	 */
	public String customTitle() {
		return labels.customTitle();
	}

	/**
	 * The session title the CLI generated automatically from the conversation. Read-only:
	 * the CLI maintains it.
	 * @return the generated title, or {@code null} when none was recorded
	 */
	public String aiTitle() {
		return labels.aiTitle();
	}

	/**
	 * The best available display title, the way Claude Code's own session pickers choose
	 * one: the user-set {@link #customTitle()} when present, otherwise the generated
	 * {@link #aiTitle()}.
	 * @return the display title, or {@code null} when the session has neither
	 */
	public String displayTitle() {
		return customTitle() != null ? customTitle() : aiTitle();
	}

	/**
	 * Sets this session's {@linkplain #tag() tag} — its custom-group name in the Claude
	 * Code desktop app — by appending a {@code {"type":"tag",...}} line to the transcript
	 * (the CLI's own storage for tags; latest line wins) and updating the in-memory
	 * {@link #labels()} in one step.
	 * @param tag the tag; leading/trailing whitespace is trimmed, matching the CLI
	 * @throws IllegalArgumentException if {@code tag} is {@code null} or blank (use
	 * {@link #clearTag()} to remove a tag — the same "use null to clear" rule the official
	 * SDK's {@code tagSession} enforces is split into two explicit methods here)
	 * @throws IOException if the transcript is missing/empty or the append fails
	 */
	public void setTag(String tag) throws IOException {
		if (tag == null || tag.trim().isEmpty()) {
			throw new IllegalArgumentException("tag must be non-empty (use clearTag() to clear)");
		}
		String trimmed = tag.trim();
		SessionLabels.appendTagLine(file, sessionId, trimmed);
		labels.tagValue(trimmed);
	}

	/**
	 * Clears this session's {@linkplain #tag() tag} by appending an empty tag line
	 * ({@code "tag":""}) to the transcript, the CLI's representation of "no tag".
	 * @throws IOException if the transcript is missing/empty or the append fails
	 */
	public void clearTag() throws IOException {
		SessionLabels.appendTagLine(file, sessionId, null);
		labels.tagValue(null);
	}

	/**
	 * Sets this session's {@linkplain #customTitle() custom title} (a rename, like the
	 * CLI's {@code /rename}) by appending a {@code {"type":"custom-title",...}} line to the
	 * transcript and updating the in-memory {@link #labels()} in one step.
	 * @param title the title; leading/trailing whitespace is trimmed, matching the CLI
	 * @throws IllegalArgumentException if {@code title} is {@code null} or blank (the CLI
	 * offers no way to un-rename a session)
	 * @throws IOException if the transcript is missing/empty or the append fails
	 */
	public void setCustomTitle(String title) throws IOException {
		if (title == null || title.trim().isEmpty()) {
			throw new IllegalArgumentException("title must be non-empty");
		}
		String trimmed = title.trim();
		SessionLabels.appendCustomTitleLine(file, sessionId, trimmed);
		labels.customTitleValue(trimmed);
	}

	/**
	 * Archives this session — its transcript, its {@code <id>.meta} metadata, the working
	 * directory's AI-memory folder, its task list, and its entire working directory tree — to
	 * {@code targetArchive} as a single portable file (see {@link SessionArchive}). The working directory comes from {@link #workingDirectory()} and the
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
	public String archiveTo(String targetArchive) throws IOException {
		if (workingDirectory == null) {
			throw new IllegalStateException("Cannot infer the working directory for session " + sessionId
					+ " (no cwd recorded in its transcript); use SessionArchive.create(sessionId, "
					+ "workingDir, ...) with an explicit directory");
		}
		Path folder = Path.of(file).getParent();
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
		return SessionArchive.create(sessionId, workingDirectory, targetArchive, projectsRoot.toString());
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
