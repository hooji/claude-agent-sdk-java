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
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	/**
	 * Loads the transcripts for the sessions executed in {@code workingDirectory} — the
	 * directory the user actually ran Claude in, not the storage location. The
	 * corresponding storage directory under the projects root is resolved via
	 * {@link #projectsDirFor(Path)}.
	 * @param workingDirectory the directory Claude sessions were executed in
	 * @return the loaded transcripts; empty (no sessions) if none exist yet
	 */
	public static TranscriptDirectory forWorkingDirectory(Path workingDirectory) throws IOException {
		return forWorkingDirectory(workingDirectory, projectsRoot(), false);
	}

	/**
	 * Variant of {@link #forWorkingDirectory(Path)} that can skip parsing the transcripts (see
	 * {@link #load(Path, boolean)}), for a fast metadata-only scan.
	 */
	public static TranscriptDirectory forWorkingDirectory(Path workingDirectory, boolean dontLoadTranscripts)
			throws IOException {
		return forWorkingDirectory(workingDirectory, projectsRoot(), dontLoadTranscripts);
	}

	/**
	 * Variant of {@link #forWorkingDirectory(Path)} with an explicit projects root (the
	 * directory holding the per-working-directory transcript folders).
	 */
	public static TranscriptDirectory forWorkingDirectory(Path workingDirectory, Path projectsRoot)
			throws IOException {
		return forWorkingDirectory(workingDirectory, projectsRoot, false);
	}

	/**
	 * Variant of {@link #forWorkingDirectory(Path)} with an explicit projects root that can skip
	 * parsing the transcripts (see {@link #load(Path, boolean)}), for a fast metadata-only scan.
	 */
	public static TranscriptDirectory forWorkingDirectory(Path workingDirectory, Path projectsRoot,
			boolean dontLoadTranscripts) throws IOException {
		Path dir = projectsDirFor(workingDirectory, projectsRoot);
		if (!Files.isDirectory(dir)) {
			return new TranscriptDirectory(dir, List.of(), List.of());
		}
		return load(dir, dontLoadTranscripts);
	}

	/**
	 * Loads the transcripts for <em>every</em> working directory recorded under {@code
	 * projectsRoot} — i.e. every Claude Code session on this machine — returning one
	 * {@link TranscriptDirectory} per working directory (folders with no transcripts are
	 * skipped). The global counterpart to {@link #forWorkingDirectory(Path)}, which scopes to a
	 * single working directory.
	 * @param projectsRoot the projects root holding the per-working-directory transcript folders
	 * @return one loaded directory per non-empty working-directory folder, ordered by folder name
	 */
	public static List<TranscriptDirectory> allUnder(Path projectsRoot) throws IOException {
		return allUnder(projectsRoot, false);
	}

	/**
	 * Variant of {@link #allUnder(Path)} that can skip parsing the transcripts (see
	 * {@link #load(Path, boolean)}), for a fast metadata-only scan of every working directory.
	 */
	public static List<TranscriptDirectory> allUnder(Path projectsRoot, boolean dontLoadTranscripts)
			throws IOException {
		if (!Files.isDirectory(projectsRoot)) {
			return List.of();
		}
		List<Path> dirs;
		try (Stream<Path> s = Files.list(projectsRoot)) {
			dirs = s.filter(Files::isDirectory).sorted().toList();
		}
		List<TranscriptDirectory> out = new ArrayList<>();
		for (Path d : dirs) {
			TranscriptDirectory loaded = load(d, dontLoadTranscripts);
			if (!loaded.sessions().isEmpty()) {
				out.add(loaded);
			}
		}
		return List.copyOf(out);
	}

	/** {@link #allUnder(Path)} using the default {@link #projectsRoot()}. */
	public static List<TranscriptDirectory> allUnder() throws IOException {
		return allUnder(projectsRoot(), false);
	}

	/**
	 * {@link #allUnder(Path, boolean)} using the default {@link #projectsRoot()}, for a fast
	 * metadata-only scan of every working directory.
	 */
	public static List<TranscriptDirectory> allUnder(boolean dontLoadTranscripts) throws IOException {
		return allUnder(projectsRoot(), dontLoadTranscripts);
	}

	/**
	 * The Claude Code projects root holding all transcript folders:
	 * {@code $CLAUDE_CONFIG_DIR/projects} when the {@code CLAUDE_CONFIG_DIR} environment
	 * variable (or the {@code claude.config.dir} system property, which takes precedence)
	 * is set, otherwise {@code ~/.claude/projects}.
	 */
	public static Path projectsRoot() {
		String configDir = System.getProperty("claude.config.dir");
		if (configDir == null || configDir.isBlank()) {
			configDir = System.getenv("CLAUDE_CONFIG_DIR");
		}
		Path base = configDir == null || configDir.isBlank()
				? Path.of(System.getProperty("user.home"), ".claude") : Path.of(configDir);
		return base.resolve("projects");
	}

	/**
	 * Maps a session's working directory (the directory the user ran Claude in) to the
	 * folder under the default projects root where Claude Code stores that directory's
	 * transcripts. Symbolic links in the path are resolved first, because Claude Code keys
	 * transcript storage by the <em>canonical</em> path (e.g. a session run in
	 * {@code /Users/nat/shared/x} where {@code shared} links to
	 * {@code /Volumes/My Shared Files/shared} is stored under
	 * {@code -Volumes-My-Shared-Files-shared-x}).
	 * @param workingDirectory the directory Claude sessions were executed in
	 * @return the transcript folder (which may not exist yet)
	 */
	public static Path projectsDirFor(Path workingDirectory) {
		return projectsDirFor(workingDirectory, projectsRoot());
	}

	/** Variant of {@link #projectsDirFor(Path)} with an explicit projects root. */
	public static Path projectsDirFor(Path workingDirectory, Path projectsRoot) {
		return projectsRoot.resolve(sanitize(canonicalize(workingDirectory)));
	}

	/**
	 * Claude Code names a working directory's transcript folder by replacing every
	 * non-alphanumeric character of its canonical path with {@code '-'} (verified against
	 * the CLI: {@code '/'}, {@code '.'}, {@code '_'} and spaces all map to {@code '-'}).
	 */
	static String sanitize(Path realPath) {
		return realPath.toString().replaceAll("[^a-zA-Z0-9]", "-");
	}

	/** Resolves symlinks when the path exists; otherwise just absolutizes/normalizes. */
	private static Path canonicalize(Path dir) {
		try {
			return dir.toRealPath();
		}
		catch (IOException e) {
			return dir.toAbsolutePath().normalize();
		}
	}

	/** Loads and analyzes every {@code *.jsonl} transcript directly under {@code directory}. */
	public static TranscriptDirectory load(Path directory) throws IOException {
		return load(directory, false);
	}

	/**
	 * Loads every {@code *.jsonl} transcript directly under {@code directory}, optionally skipping
	 * the (relatively expensive) transcript parse and fork analysis.
	 *
	 * <p>When {@code dontLoadTranscripts} is {@code true}, each {@link Session} is populated only
	 * with its identity and metadata — {@link Session#sessionId()}, {@link Session#file()},
	 * {@link Session#agentSession()}, {@link Session#agentId()} and {@link Session#metaData()} —
	 * while {@code entries}, {@code messages}, {@code segments} and {@code forkMarkers} are left
	 * empty and no {@link ConversationFamily} fork analysis is performed (so {@link #families()}
	 * is empty). This is a fast scan for building a session browser; load a chosen session fully
	 * (default {@code load}) before replaying, archiving, or inspecting its history.
	 * @param directory the transcript folder to load
	 * @param dontLoadTranscripts {@code true} to skip parsing transcripts (metadata-only scan)
	 */
	public static TranscriptDirectory load(Path directory, boolean dontLoadTranscripts) throws IOException {
		List<Path> files;
		try (Stream<Path> s = Files.list(directory)) {
			files = s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
					.sorted()
					.toList();
		}

		if (dontLoadTranscripts) {
			List<Session> lite = new ArrayList<>();
			for (Path f : files) {
				String sessionId = stripExtension(f.getFileName().toString());
				boolean agent = sessionId.startsWith("agent-");
				String agentId = agent ? sessionId.substring("agent-".length()) : null;
				lite.add(new Session(sessionId, f, agent, agentId, List.of(), List.of(), List.of(), List.of(),
						readMetaData(f)));
			}
			return new TranscriptDirectory(directory, List.copyOf(lite), List.of());
		}

		ObjectMapper mapper = new ObjectMapper();
		MessageParser parser = new MessageParser();

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

		// Per-session messages and segments first: fork markers need every session's parentage.
		Map<String, List<TranscriptEntry>> messagesBySession = new HashMap<>();
		Map<String, List<ForkSegment>> segmentsBySession = new HashMap<>();
		Map<String, String> parentOf = new HashMap<>(); // derived as in Session#parentSessionId
		for (Raw r : raws) {
			List<TranscriptEntry> messages = r.entries().stream().filter(TranscriptEntry::hasUuid).toList();
			List<ForkSegment> segments = computeSegments(messages, uuidSessions, sessionUuids);
			messagesBySession.put(r.sessionId(), messages);
			segmentsBySession.put(r.sessionId(), segments);
			parentOf.put(r.sessionId(),
					segments.size() < 2 ? null : segments.get(segments.size() - 2).originSessionId());
		}

		// Build Sessions with their fork partition and precomputed fork markers (the sibling
		// lists require directory-wide knowledge, so a Session can replay itself afterwards).
		List<Session> sessions = new ArrayList<>();
		for (Raw r : raws) {
			List<ForkSegment> segments = segmentsBySession.get(r.sessionId());
			List<ForkMarker> markers = new ArrayList<>();
			for (int i = 1; i < segments.size(); i++) {
				String parent = segments.get(i - 1).originSessionId();
				String child = segments.get(i).originSessionId();
				List<String> siblings = parentOf.entrySet().stream()
						.filter(e -> parent.equals(e.getValue()) && !e.getKey().equals(child))
						.map(Map.Entry::getKey)
						.sorted()
						.toList();
				markers.add(new ForkMarker(parent, child, segments.get(i).startIndex(), siblings));
			}
			sessions.add(new Session(r.sessionId(), r.file(), r.agentSession(), r.agentId(), r.entries(),
					messagesBySession.get(r.sessionId()), segments, List.copyOf(markers), readMetaData(r.file())));
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

	/** @return the session with the given id, if loaded. */
	public Optional<Session> byId(String sessionId) {
		return sessions.stream().filter(s -> s.sessionId().equals(sessionId)).findFirst();
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
	 * The most recently modified main session — the same notion of "most recent" the CLI's
	 * {@code --continue} resumes. Best-effort when several sessions run concurrently in
	 * the same directory.
	 * @return the most recent main session, or {@code null} if none are loaded
	 */
	public Session mostRecentSession() {
		return mainSessions().stream().max(Comparator.comparing(s -> {
			try {
				return Files.getLastModifiedTime(s.file());
			}
			catch (IOException e) {
				return FileTime.fromMillis(0);
			}
		})).orElse(null);
	}

	/**
	 * Replays a session's full history as a stream of SDK {@link Message}s. Id-addressed
	 * convenience that delegates to {@link Session#replay()} — see
	 * {@link Session#replayMessages()} for the replay semantics (fork markers, raw
	 * passthrough, terminal {@link HistoryEnd}).
	 * @param sessionId the session to replay
	 * @return the replayed messages as a {@link Flux} (cold; emits on subscribe)
	 */
	public Flux<Message> replay(String sessionId) {
		return Flux.fromIterable(replayMessages(sessionId));
	}

	/**
	 * Eager (non-reactive) form of {@link #replay(String)}. Id-addressed convenience that
	 * delegates to {@link Session#replayMessages()}.
	 * @param sessionId the session to replay
	 * @return the ordered replay messages
	 * @throws java.util.NoSuchElementException if no such session is loaded
	 */
	public List<Message> replayMessages(String sessionId) {
		return byId(sessionId).orElseThrow().replayMessages();
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

	/** Reads the {@code <id>.meta} sidecar for a transcript file (empty map when absent). */
	private static Map<String, Serializable> readMetaData(Path transcriptFile) throws IOException {
		return SessionMetadata.readFromFile(SessionMetadata.fileFor(transcriptFile));
	}

	private static String shortId(String id) {
		if (id == null) {
			return "?";
		}
		return id.length() > 8 ? id.substring(0, 8) : id;
	}
}
