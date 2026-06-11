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
import java.util.ArrayList;
import java.util.List;

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
 * @param entries every line of the file, in order, retained losslessly
 * @param messages the uuid-bearing subset of {@code entries} (the lineage carrier the fork
 * partition indexes into)
 * @param segments the fork partition over {@code messages}; size 1 when the session has no
 * fork points (it is then a single segment owned by this session)
 * @param forkMarkers one precomputed {@link ForkMarker} per segment boundary (so always
 * {@code segments.size() - 1} of them), in order
 */
public record Session(String sessionId, Path file, boolean agentSession, String agentId,
		List<TranscriptEntry> entries, List<TranscriptEntry> messages, List<ForkSegment> segments,
		List<ForkMarker> forkMarkers) {

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
