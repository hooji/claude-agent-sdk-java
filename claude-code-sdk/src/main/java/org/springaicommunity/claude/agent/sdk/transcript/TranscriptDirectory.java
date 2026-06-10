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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springaicommunity.claude.agent.sdk.parsing.MessageParser;
import org.springaicommunity.claude.agent.sdk.types.Message;
import reactor.core.publisher.Flux;

/**
 * Stage 1 of transcript discovery: loads <em>all</em> session transcripts under a directory
 * fully into memory, mirroring the on-disk structure and recovering the fork relationships.
 *
 * <p>After {@link #load(Path)} returns, nothing further needs to be read from disk — all
 * higher-level functionality (Markdown rendering, replay, navigation, regeneration) is built
 * on this in-memory structure. Every line of every file is retained losslessly (see
 * {@link TranscriptEntry#raw()}), so {@link #regenerate(Path)} can write the transcripts back
 * JSON-equivalently.
 *
 * <p><b>Fork recovery.</b> Claude Code stores a {@code --fork-session} by copying the parent's
 * full history into the child file (keeping each message's original {@code uuid} but
 * re-stamping {@code sessionId}). So a session's uuid set is a superset of each ancestor's.
 * For each uuid-bearing message, its origin session is the loaded session with the smallest
 * uuid set that contains it; contiguous runs of the same origin form the {@link ForkSegment}
 * partition.
 *
 * @param directory the loaded directory
 * @param sessions every session loaded (main sessions and {@code agent-*} sidechain sessions)
 * @param families independent conversations (grouped by shared root), each with its fork tree
 */
public record TranscriptDirectory(Path directory, List<Session> sessions, List<ConversationFamily> families) {

	/** Loads and analyzes every {@code *.jsonl} transcript directly under {@code directory}. */
	public static TranscriptDirectory load(Path directory) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		MessageParser parser = new MessageParser();

		List<Path> files;
		try (Stream<Path> s = Files.list(directory)) {
			files = s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
					.sorted()
					.toList();
		}

		// Local holder for the raw parse, before fork analysis (which needs all files).
		record Raw(String sessionId, Path file, boolean agentSession, String agentId, List<TranscriptEntry> entries) {
		}

		List<Raw> raws = new ArrayList<>();
		for (Path f : files) {
			List<TranscriptEntry> entries = new ArrayList<>();
			int lineNo = 0;
			for (String line : Files.readAllLines(f)) {
				lineNo++;
				if (line.isBlank()) {
					continue;
				}
				JsonNode node;
				try {
					node = mapper.readTree(line);
				}
				catch (Exception e) {
					continue; // not JSON — skip (transcripts are JSONL)
				}
				if (!node.isObject()) {
					continue;
				}
				Message message = null;
				try {
					message = parser.parseMessage(line);
				}
				catch (Exception ignored) {
					// non-conversation / bookkeeping line: keep raw, leave message null
				}
				entries.add(new TranscriptEntry(lineNo, text(node, "uuid"), text(node, "parentUuid"),
						text(node, "type"), node.path("isSidechain").asBoolean(false), text(node, "agentId"),
						text(node, "timestamp"), message, node));
			}
			String sessionId = stripExtension(f.getFileName().toString());
			boolean agent = sessionId.startsWith("agent-");
			String agentId = agent ? sessionId.substring("agent-".length()) : null;
			raws.add(new Raw(sessionId, f, agent, agentId, List.copyOf(entries)));
		}

		// Per-session uuid sets + global uuid -> candidate sessions, for provenance.
		Map<String, Set<String>> sessionUuids = new HashMap<>();
		Map<String, List<String>> uuidSessions = new HashMap<>();
		for (Raw r : raws) {
			Set<String> us = new HashSet<>();
			for (TranscriptEntry e : r.entries()) {
				if (e.uuid() != null) {
					us.add(e.uuid());
				}
			}
			sessionUuids.put(r.sessionId(), us);
			for (String u : us) {
				uuidSessions.computeIfAbsent(u, k -> new ArrayList<>()).add(r.sessionId());
			}
		}

		// Build Sessions with their fork partition.
		List<Session> sessions = new ArrayList<>();
		for (Raw r : raws) {
			List<TranscriptEntry> messages = r.entries().stream().filter(TranscriptEntry::hasUuid).toList();
			List<ForkSegment> segments = computeSegments(messages, uuidSessions, sessionUuids);
			sessions.add(new Session(r.sessionId(), r.file(), r.agentSession(), r.agentId(), r.entries(), messages,
					segments));
		}

		List<ConversationFamily> families = buildFamilies(sessions);
		return new TranscriptDirectory(directory, List.copyOf(sessions), List.copyOf(families));
	}

	/** Partitions a session's messages into contiguous same-origin runs. */
	private static List<ForkSegment> computeSegments(List<TranscriptEntry> messages,
			Map<String, List<String>> uuidSessions, Map<String, Set<String>> sessionUuids) {
		List<ForkSegment> segments = new ArrayList<>();
		String currentOrigin = null;
		int start = 0;
		int count = 0;
		for (int i = 0; i < messages.size(); i++) {
			String origin = originOf(messages.get(i).uuid(), uuidSessions, sessionUuids);
			if (currentOrigin == null) {
				currentOrigin = origin;
				start = 0;
				count = 1;
			}
			else if (origin.equals(currentOrigin)) {
				count++;
			}
			else {
				segments.add(new ForkSegment(currentOrigin, start, count));
				currentOrigin = origin;
				start = i;
				count = 1;
			}
		}
		if (currentOrigin != null) {
			segments.add(new ForkSegment(currentOrigin, start, count));
		}
		return List.copyOf(segments);
	}

	/** Origin = the candidate session with the smallest uuid set (most ancestral). */
	private static String originOf(String uuid, Map<String, List<String>> uuidSessions,
			Map<String, Set<String>> sessionUuids) {
		List<String> candidates = uuidSessions.getOrDefault(uuid, List.of());
		return candidates.stream()
				.min(Comparator.<String>comparingInt(sid -> sessionUuids.get(sid).size()).thenComparing(sid -> sid))
				.orElse(null);
	}

	/** Groups main sessions into families by shared root and builds each fork tree. */
	private static List<ConversationFamily> buildFamilies(List<Session> sessions) {
		Map<String, List<Session>> byRoot = new HashMap<>();
		for (Session s : sessions) {
			if (s.agentSession()) {
				continue; // sub-agent sidechains are not fork families
			}
			byRoot.computeIfAbsent(s.rootSessionId(), k -> new ArrayList<>()).add(s);
		}
		List<ConversationFamily> families = new ArrayList<>();
		for (Map.Entry<String, List<Session>> e : byRoot.entrySet()) {
			List<Session> members = e.getValue();
			Map<String, Session> byId = new HashMap<>();
			members.forEach(s -> byId.put(s.sessionId(), s));
			Session root = members.stream()
					.filter(s -> s.parentSessionId() == null)
					.findFirst()
					.orElse(members.get(0));
			ForkNode tree = buildNode(root, members);
			families.add(new ConversationFamily(e.getKey(), tree, List.copyOf(members)));
		}
		families.sort(Comparator.comparing(ConversationFamily::rootSessionId));
		return List.copyOf(families);
	}

	private static ForkNode buildNode(Session s, List<Session> family) {
		List<ForkNode> children = family.stream()
				.filter(c -> s.sessionId().equals(c.parentSessionId()))
				.sorted(Comparator.comparing(Session::sessionId))
				.map(c -> buildNode(c, family))
				.toList();
		return new ForkNode(s, s.forkPointIndex(), children);
	}

	/**
	 * Converts a real (symlink-resolved) working directory path to the sanitized folder
	 * name Claude Code uses under {@code ~/.claude/projects/}. Every character that is not
	 * ASCII alphanumeric is replaced with {@code -}, matching Claude Code's own naming
	 * convention (handles slashes, dots, spaces, and other special characters).
	 * @param realPath the symlink-resolved absolute path
	 * @return the sanitized folder name
	 */
	public static String sanitize(Path realPath) {
		return realPath.toString().replaceAll("[^a-zA-Z0-9]", "-");
	}

	/**
	 * Returns an empty {@link TranscriptDirectory} for a directory that does not yet
	 * exist on disk (e.g. no sessions have been started in that working directory yet).
	 * @param directory the (absent) transcript directory path
	 * @return an empty instance with no sessions or families
	 */
	public static TranscriptDirectory empty(Path directory) {
		return new TranscriptDirectory(directory, List.of(), List.of());
	}

	/** @return the session with the given id, or {@code null} if not loaded. */
	public Session byId(String sessionId) {
		return sessions.stream().filter(s -> s.sessionId().equals(sessionId)).findFirst().orElse(null);
	}

	/** @return only the main (non sub-agent) sessions. */
	public List<Session> mainSessions() {
		return sessions.stream().filter(s -> !s.agentSession()).toList();
	}

	/** @return only the sub-agent sidechain sessions. */
	public List<Session> agentSessions() {
		return sessions.stream().filter(Session::agentSession).toList();
	}

	/**
	 * Replays a session's full history (root through this leaf) as a stream of SDK
	 * {@link Message}s, in a form compatible with live message handling. <b>Every</b>
	 * transcript line is emitted, in file order: conversation lines as their parsed
	 * {@link Message} type, and all other lines (e.g. {@code attachment},
	 * {@code queue-operation}, {@code mode}) as a {@link RawTranscriptMessage} carrying the
	 * raw type and JSON — so the consumer can choose to surface or hide each. A
	 * {@link ForkMarker} is emitted at each fork boundary and a terminal {@link HistoryEnd}
	 * signals completion.
	 * @param sessionId the session to replay
	 * @return the replayed messages as a {@link Flux} (cold; emits on subscribe)
	 */
	public Flux<Message> replay(String sessionId) {
		return Flux.fromIterable(replayMessages(sessionId));
	}

	/**
	 * Eager (non-reactive) form of {@link #replay(String)}: the full replay as a list, with
	 * {@link ForkMarker}s at fork boundaries and a trailing {@link HistoryEnd}.
	 * @param sessionId the session to replay
	 * @return the ordered replay messages
	 * @throws java.util.NoSuchElementException if no such session is loaded
	 */
	public List<Message> replayMessages(String sessionId) {
		Session s = byId(sessionId);
		if (s == null) {
			throw new java.util.NoSuchElementException("No session loaded with id: " + sessionId);
		}
		List<ForkSegment> segments = s.segments();
		List<Message> out = new ArrayList<>();
		int uuidPos = 0; // position within the uuid-bearing message list (the partition coordinate)
		int seg = 0;
		for (TranscriptEntry e : s.entries()) {
			if (e.hasUuid()) {
				// Crossing into a later segment: emit a fork marker before this message.
				while (seg + 1 < segments.size() && uuidPos >= segments.get(seg + 1).startIndex()) {
					seg++;
					String parent = segments.get(seg - 1).originSessionId();
					String child = segments.get(seg).originSessionId();
					out.add(new ForkMarker(parent, child, segments.get(seg).startIndex(), siblingsOf(parent, child)));
				}
				uuidPos++;
			}
			// Emit EVERY line: parsed conversation message, or a raw passthrough otherwise.
			out.add(e.hasMessage() ? e.message() : new RawTranscriptMessage(e.type(), e.uuid(), e.raw()));
		}
		out.add(new HistoryEnd(sessionId, s.messages().size()));
		return out;
	}

	/** Other sessions forked from {@code parent}, excluding {@code exclude} (for nav). */
	private List<String> siblingsOf(String parent, String exclude) {
		return sessions.stream()
				.filter(o -> parent.equals(o.parentSessionId()) && !o.sessionId().equals(exclude))
				.map(Session::sessionId)
				.sorted()
				.toList();
	}

	/**
	 * Writes every loaded session back to {@code destDir} under its original filename, using
	 * each entry's retained raw JSON. The result is JSON-equivalent to the source (not
	 * necessarily byte-identical), which is the basis of the round-trip fidelity test.
	 */
	public void regenerate(Path destDir) throws IOException {
		Files.createDirectories(destDir);
		ObjectMapper mapper = new ObjectMapper();
		for (Session s : sessions) {
			Path out = destDir.resolve(s.file().getFileName().toString());
			List<String> lines = new ArrayList<>(s.entries().size());
			for (TranscriptEntry e : s.entries()) {
				try {
					lines.add(mapper.writeValueAsString(e.raw()));
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}
			Files.write(out, lines);
		}
	}

	/** Renders the directory structure as Markdown: independent conversations, then forks. */
	public String toMarkdown() {
		StringBuilder sb = new StringBuilder();
		sb.append("# Transcript directory\n\n");
		sb.append("`").append(directory).append("`\n\n");
		sb.append("- sessions: ").append(sessions.size()).append(" (main: ").append(mainSessions().size())
				.append(", sub-agent: ").append(agentSessions().size()).append(")\n");
		sb.append("- independent conversations: ").append(families.size()).append("\n\n");

		int n = 1;
		for (ConversationFamily fam : families) {
			sb.append("## Conversation ").append(n++).append(" — root `").append(shortId(fam.rootSessionId()))
					.append("`\n\n");
			renderNode(fam.tree(), 0, sb);
			sb.append("\n");
		}

		List<Session> agents = agentSessions();
		if (!agents.isEmpty()) {
			sb.append("## Sub-agent sessions\n\n");
			for (Session a : agents) {
				sb.append("- `").append(a.sessionId()).append("` (agentId ").append(shortId(a.agentId()))
						.append(", ").append(a.messages().size()).append(" messages)\n");
			}
		}
		return sb.toString();
	}

	private void renderNode(ForkNode node, int depth, StringBuilder sb) {
		Session s = node.session();
		String indent = "  ".repeat(depth);
		sb.append(indent).append("- `").append(shortId(s.sessionId())).append("`");
		if (s.isFork()) {
			sb.append(" — forked from `").append(shortId(s.parentSessionId())).append("` after message ")
					.append(node.forkPointInParent());
		}
		else {
			sb.append(" — original");
		}
		sb.append(" (").append(s.messages().size()).append(" messages)\n");
		// segment breakdown
		for (ForkSegment seg : s.segments()) {
			sb.append(indent).append("  - segment from `").append(shortId(seg.originSessionId())).append("`: msgs [")
					.append(seg.startIndex()).append("..").append(seg.endIndexExclusive() - 1).append("] (")
					.append(seg.count()).append(")\n");
		}
		for (ForkNode child : node.children()) {
			renderNode(child, depth + 1, sb);
		}
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(0, dot);
	}

	private static String shortId(String id) {
		if (id == null) {
			return "?";
		}
		return id.length() > 8 ? id.substring(0, 8) : id;
	}
}
