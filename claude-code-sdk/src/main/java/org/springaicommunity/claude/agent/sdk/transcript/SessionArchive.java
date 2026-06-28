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
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Packages a single Claude Code session — its transcript, its SDK metadata, <em>and</em> the
 * entire working directory tree it ran in — into one portable, compressed archive file, so a
 * session and all its associated files can be treated as a single "thing" that is saved, copied,
 * and moved around.
 *
 * <p>This is the full-snapshot counterpart to {@link SessionClone}: where a clone always forks
 * to a <em>new</em> id in a sibling directory, an archive is a relocatable backup that, on
 * {@link #restore restore}, defaults to keeping the original session id (so the restored copy
 * <em>is</em> the same session) with the option to mint a new one.
 *
 * <p><b>Metadata.</b> An archive carries the session's metadata by embedding its {@code <id>.meta}
 * sidecar verbatim (see {@link SessionMetadata}); there is no separate name/description — store
 * those as ordinary keys in the {@link Session#metaData() metadata map}. Archiving copies the
 * {@code .meta} bytes as-is (no deserialization, so the value classes are not needed to
 * <em>create</em> an archive); {@link #readMetaData} and {@link #restore} do read it back.
 *
 * <p><b>Scope.</b> An archive contains only the <em>specified</em> session's transcript (a fork
 * already embeds its ancestors' history, so it stays self-contained) — never the sibling
 * sessions that happen to share the working directory's transcript folder.
 *
 * <h2>Archive layout</h2> A ZIP (no external dependency) with:
 * <pre>
 *   manifest.json                  provenance (see {@link Manifest})
 *   metadata.ser                   the session's {@code .meta} bytes (Java-serialized map; omitted if no .meta)
 *   transcript/&lt;sessionId&gt;.jsonl   the one session's transcript (verbatim)
 *   transcript/&lt;sessionId&gt;/...     externalized tool-result sidecar files, if any
 *   workdir/...                    the entire working-directory tree
 * </pre>
 *
 * <p><b>Caveats.</b> The working tree is captured in full — it may be large and may contain
 * secrets (e.g. {@code .env}, credentials, {@code .git}); an archive is a single easily-shared
 * file. Metadata values are stored with Java serialization, so reading them back ({@link
 * #readMetaData}, {@link #restore} followed by a load) requires the same classes on the
 * classpath; treat an untrusted archive with the same caution as any Java deserialization.
 */
public final class SessionArchive {

	/** Bumped if the on-disk archive layout changes incompatibly. */
	static final int SCHEMA_VERSION = 2;

	private static final String MANIFEST = "manifest.json";

	private static final String METADATA = "metadata.ser";

	private static final String TRANSCRIPT_PREFIX = "transcript/";

	private static final String WORKDIR_PREFIX = "workdir/";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SessionArchive() {
	}

	/**
	 * The provenance recorded in an archive, readable without extracting it (see
	 * {@link #readManifest}). The metadata itself is <em>not</em> included here — it requires the
	 * value classes on the classpath and is read separately via {@link #readMetaData}.
	 *
	 * @param schemaVersion the archive format version
	 * @param sessionId the original (archived) session id
	 * @param originalWorkingDir the absolute working directory the session ran in (the key used
	 * to rewrite paths on restore)
	 * @param createdAt when the archive was written ({@code null} if unparseable)
	 * @param messageCount number of conversation messages in the transcript
	 * @param hasMetaData whether the archive carries an embedded {@code .meta} (which may itself
	 * be an explicitly-empty map)
	 */
	public record Manifest(int schemaVersion, String sessionId, String originalWorkingDir, Instant createdAt,
			int messageCount, boolean hasMetaData) {
	}

	/**
	 * The result of a {@link #restore}.
	 *
	 * @param sessionId the restored session id (the original, unless a new one was requested)
	 * @param workingDirectory the directory the working tree was restored into
	 * @param transcriptPath the re-homed transcript file (under the projects root)
	 * @param manifest the archive's manifest
	 */
	public record RestoreResult(String sessionId, String workingDirectory, String transcriptPath, Manifest manifest) {
	}

	/**
	 * Archives {@code sessionId} (which ran in {@code workingDir}) to {@code targetArchive},
	 * using the default Claude projects root.
	 * @return the archive file path actually written
	 */
	public static String create(String sessionId, String workingDir, String targetArchive) throws IOException {
		return create(sessionId, workingDir, targetArchive, TranscriptDirectory.projectsRoot());
	}

	/**
	 * Archives {@code sessionId} (which ran in {@code workingDir}) to {@code targetArchive}, with
	 * an explicit {@code projectsRoot}. The session's metadata is picked up from its
	 * {@code <id>.meta} sidecar (omitted if there is none).
	 * @return the archive file path actually written (absolute, normalized)
	 * @throws IllegalArgumentException if the working dir/transcript can't be found or the target
	 * archive would sit inside the working tree being captured
	 */
	public static String create(String sessionId, String workingDir, String targetArchive, String projectsRoot)
			throws IOException {
		Path workingDirPath = Path.of(workingDir);
		if (!Files.isDirectory(workingDirPath)) {
			throw new IllegalArgumentException("workingDir is not a directory: " + workingDir);
		}
		String srcReal = workingDirPath.toRealPath().toString();
		String transcript = Transcripts.locateTranscript(projectsRoot, srcReal, sessionId);

		// The archive must live outside the tree we're about to walk, or it would try to
		// capture itself mid-write.
		Path archiveAbs = Path.of(targetArchive).toAbsolutePath().normalize();
		if (archiveAbs.startsWith(srcReal)) {
			throw new IllegalArgumentException("targetArchive must not be inside workingDir: " + targetArchive);
		}
		if (archiveAbs.getParent() != null) {
			Files.createDirectories(archiveAbs.getParent());
		}

		// The metadata sidecar travels verbatim — copied as bytes, never deserialized here, so
		// creating an archive does not require the metadata value classes on the classpath.
		Path metaFile = Path.of(SessionMetadata.fileFor(transcript));
		byte[] metaBytes = Files.isRegularFile(metaFile) ? Files.readAllBytes(metaFile) : null;

		ObjectNode manifest = buildManifest(sessionId, srcReal, countMessages(transcript), metaBytes != null);

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archiveAbs))) {
			writeBytes(zip, MANIFEST, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
			if (metaBytes != null) {
				writeBytes(zip, METADATA, metaBytes);
			}
			writeBytes(zip, TRANSCRIPT_PREFIX + sessionId + ".jsonl", Files.readAllBytes(Path.of(transcript)));
			Path aux = Path.of(transcript).getParent().resolve(sessionId);
			if (Files.isDirectory(aux)) {
				addTree(zip, aux.toString(), TRANSCRIPT_PREFIX + sessionId + "/");
			}
			addTree(zip, srcReal, WORKDIR_PREFIX);
		}
		return archiveAbs.toString();
	}

	/**
	 * Restores an archive into the fresh working directory {@code targetWorkingDir}, keeping the
	 * original session id, using the default projects root.
	 */
	public static RestoreResult restore(String archive, String targetWorkingDir) throws IOException {
		return restore(archive, targetWorkingDir, false, TranscriptDirectory.projectsRoot());
	}

	/**
	 * Restores an archive into {@code targetWorkingDir}, optionally minting a new session id
	 * (a "fork on restore"), using the default projects root.
	 */
	public static RestoreResult restore(String archive, String targetWorkingDir, boolean newSessionId)
			throws IOException {
		return restore(archive, targetWorkingDir, newSessionId, TranscriptDirectory.projectsRoot());
	}

	/**
	 * Restores an archive into {@code targetWorkingDir}: inflates the working tree, then re-homes
	 * the transcript under {@code projectsRoot} with every path reference rewritten from the
	 * archived working directory to {@code targetWorkingDir}, and materializes the session's
	 * {@code <id>.meta} sidecar (named for the restore id) when the archive carries metadata.
	 * @param newSessionId {@code false} keeps the archived id (a faithful restore/move);
	 * {@code true} assigns a fresh id, forking an independent line on restore
	 * @return the restored id, working directory, transcript path, and the manifest
	 * @throws IllegalArgumentException if the target is non-empty, or (when keeping the id) a
	 * transcript with that id already exists at the destination
	 */
	public static RestoreResult restore(String archive, String targetWorkingDir, boolean newSessionId,
			String projectsRoot) throws IOException {
		if (!Files.isRegularFile(Path.of(archive))) {
			throw new IllegalArgumentException("archive is not a file: " + archive);
		}
		Path targetWorkingDirPath = Path.of(targetWorkingDir);
		if (Files.exists(targetWorkingDirPath) && Transcripts.isNonEmptyDir(targetWorkingDir)) {
			throw new IllegalArgumentException("targetWorkingDir must be empty or non-existent: " + targetWorkingDir);
		}
		Manifest manifest = readManifest(archive);
		if (manifest.sessionId() == null || manifest.originalWorkingDir() == null) {
			throw new IOException("Archive manifest is missing sessionId/originalWorkingDir: " + archive);
		}

		Files.createDirectories(targetWorkingDirPath);
		String targetReal = targetWorkingDirPath.toRealPath().toString();
		String origId = manifest.sessionId();
		String restoreId = newSessionId ? UUID.randomUUID().toString() : origId;

		Path targetProjectsDir = Path.of(projectsRoot).resolve(TranscriptDirectory.sanitize(targetReal));
		Files.createDirectories(targetProjectsDir);
		Path targetTranscript = targetProjectsDir.resolve(restoreId + ".jsonl");
		if (Files.exists(targetTranscript)) {
			throw new IllegalArgumentException("Target transcript already exists: " + targetTranscript
					+ " (restore into a fresh location, or pass newSessionId=true)");
		}

		String fromPath = manifest.originalWorkingDir();
		String toPath = targetReal;
		String auxDir = targetProjectsDir.resolve(restoreId).toString();
		Path targetMeta = targetProjectsDir.resolve(restoreId + SessionMetadata.EXTENSION);
		String transcriptFileEntry = TRANSCRIPT_PREFIX + origId + ".jsonl";
		String auxPrefix = TRANSCRIPT_PREFIX + origId + "/";

		try (ZipFile zip = new ZipFile(archive)) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				String name = e.getName();
				if (name.equals(MANIFEST)) {
					continue;
				}
				if (name.equals(METADATA)) {
					// The metadata map is opaque (no paths to re-home); copy it verbatim under the
					// restore id, so a subsequent load picks it up as this session's metaData.
					try (InputStream in = zip.getInputStream(e)) {
						Files.write(targetMeta, in.readAllBytes());
					}
				}
				else if (name.equals(transcriptFileEntry)) {
					List<String> lines;
					try (InputStream in = zip.getInputStream(e)) {
						lines = splitLines(in.readAllBytes());
					}
					Files.write(targetTranscript, Transcripts.rehomeLines(lines, fromPath, toPath, restoreId));
				}
				else if (name.startsWith(auxPrefix)) {
					extract(zip, e, safeResolve(auxDir, name.substring(auxPrefix.length())));
				}
				else if (name.startsWith(WORKDIR_PREFIX)) {
					String rel = name.substring(WORKDIR_PREFIX.length());
					if (!rel.isEmpty()) {
						extract(zip, e, safeResolve(targetReal, rel));
					}
				}
			}
		}
		return new RestoreResult(restoreId, targetWorkingDir, targetTranscript.toString(), manifest);
	}

	/**
	 * Reads an archive's {@link Manifest} (provenance) without extracting it or touching its
	 * serialized metadata.
	 */
	public static Manifest readManifest(String archive) throws IOException {
		try (ZipFile zip = new ZipFile(archive)) {
			ZipEntry e = zip.getEntry(MANIFEST);
			if (e == null) {
				throw new IOException("Not a session archive (missing " + MANIFEST + "): " + archive);
			}
			JsonNode m;
			try (InputStream in = zip.getInputStream(e)) {
				m = MAPPER.readTree(in);
			}
			return new Manifest(m.path("schemaVersion").asInt(SCHEMA_VERSION), textOrNull(m, "sessionId"),
					textOrNull(m, "originalWorkingDir"), parseInstant(textOrNull(m, "createdAt")),
					m.path("messageCount").asInt(0), m.path("hasMetaData").asBoolean(false));
		}
	}

	/**
	 * Deserializes the archive's embedded session metadata (empty if it has none). Requires the
	 * metadata value classes on the classpath.
	 * @throws IOException if the archive can't be read, or a value class is missing
	 */
	public static Map<String, Serializable> readMetaData(String archive) throws IOException {
		try (ZipFile zip = new ZipFile(archive)) {
			ZipEntry e = zip.getEntry(METADATA);
			if (e == null) {
				return new LinkedHashMap<>();
			}
			try (InputStream in = zip.getInputStream(e)) {
				return SessionMetadata.deserialize(in.readAllBytes());
			}
		}
	}

	// --- internals -----------------------------------------------------------------------

	private static ObjectNode buildManifest(String sessionId, String originalWorkingDir, int messageCount,
			boolean hasMetaData) {
		ObjectNode m = MAPPER.createObjectNode();
		m.put("schemaVersion", SCHEMA_VERSION);
		m.put("sessionId", sessionId);
		m.put("originalWorkingDir", originalWorkingDir);
		m.put("createdAt", Instant.now().toString());
		m.put("messageCount", messageCount);
		m.put("hasMetaData", hasMetaData);
		return m;
	}

	/** Counts conversation messages (uuid-bearing lines), the same notion as {@code Session.messages()}. */
	private static int countMessages(String transcript) throws IOException {
		int n = 0;
		for (String line : Files.readAllLines(Path.of(transcript))) {
			if (line.isBlank()) {
				continue;
			}
			try {
				if (MAPPER.readTree(line).hasNonNull("uuid")) {
					n++;
				}
			}
			catch (Exception ignored) {
				// not JSON — not a message
			}
		}
		return n;
	}

	/** Adds every file under {@code root} to the zip, prefixing entry names with {@code entryPrefix}. */
	private static void addTree(ZipOutputStream zip, String root, String entryPrefix) throws IOException {
		Path rootPath = Path.of(root);
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(rootPath)) {
			paths = walk.sorted().toList();
		}
		for (Path p : paths) {
			String rel = rootPath.relativize(p).toString().replace('\\', '/');
			if (rel.isEmpty()) {
				continue; // the root itself
			}
			if (Files.isDirectory(p)) {
				zip.putNextEntry(new ZipEntry(entryPrefix + rel + "/"));
				zip.closeEntry();
			}
			else {
				zip.putNextEntry(new ZipEntry(entryPrefix + rel));
				Files.copy(p, zip);
				zip.closeEntry();
			}
		}
	}

	private static void writeBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
	}

	private static void extract(ZipFile zip, ZipEntry e, String dst) throws IOException {
		Path dstPath = Path.of(dst);
		if (e.isDirectory()) {
			Files.createDirectories(dstPath);
			return;
		}
		Files.createDirectories(dstPath.getParent());
		try (InputStream in = zip.getInputStream(e)) {
			Files.copy(in, dstPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Resolves {@code rel} under {@code base}, refusing entries that escape it (zip-slip guard). */
	private static String safeResolve(String base, String rel) {
		Path baseNorm = Path.of(base).normalize();
		Path resolved = baseNorm.resolve(rel).normalize();
		if (!resolved.startsWith(baseNorm)) {
			throw new IllegalArgumentException("Illegal archive entry escapes the target directory: " + rel);
		}
		return resolved.toString();
	}

	private static List<String> splitLines(byte[] bytes) {
		List<String> lines = new ArrayList<>();
		for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
			lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
		}
		return lines;
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}

	private static Instant parseInstant(String s) {
		try {
			return s == null ? null : Instant.parse(s);
		}
		catch (Exception e) {
			return null;
		}
	}

}
