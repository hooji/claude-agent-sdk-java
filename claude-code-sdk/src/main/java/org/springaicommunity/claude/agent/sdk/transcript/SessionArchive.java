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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
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
 * Packages a single Claude Code session — its transcript <em>and</em> the entire working
 * directory tree it ran in — into one portable, compressed archive file, plus arbitrary
 * metadata, so a session and all its associated files can be treated as a single "thing" that
 * is saved, copied, and moved around.
 *
 * <p>This is the full-snapshot counterpart to {@link SessionClone}: where a clone always forks
 * to a <em>new</em> id in a sibling directory, an archive is a relocatable backup that, on
 * {@link #restore restore}, defaults to keeping the original session id (so the restored copy
 * <em>is</em> the same session) with the option to mint a new one.
 *
 * <p><b>Scope.</b> An archive contains only the <em>specified</em> session's transcript (a fork
 * already embeds its ancestors' history, so it stays self-contained) — never the sibling
 * sessions that happen to share the working directory's transcript folder.
 *
 * <h2>Archive layout</h2> A ZIP (no external dependency) with:
 * <pre>
 *   manifest.json                  provenance + name/description (see {@link Manifest})
 *   attributes.ser                 the Map&lt;String,Serializable&gt; attributes (Java-serialized; omitted if empty)
 *   transcript/&lt;sessionId&gt;.jsonl   the one session's transcript (verbatim)
 *   transcript/&lt;sessionId&gt;/...     externalized tool-result sidecar files, if any
 *   workdir/...                    the entire working-directory tree
 * </pre>
 *
 * <p><b>Caveats.</b> The working tree is captured in full — it may be large and may contain
 * secrets (e.g. {@code .env}, credentials, {@code .git}); an archive is a single easily-shared
 * file. Attribute values are stored with Java serialization, so reading them back requires the
 * same classes on the classpath; treat {@link #readAttributes} on an untrusted archive with the
 * same caution as any Java deserialization.
 */
public final class SessionArchive {

	/** Bumped if the on-disk archive layout changes incompatibly. */
	static final int SCHEMA_VERSION = 1;

	private static final String MANIFEST = "manifest.json";

	private static final String ATTRIBUTES = "attributes.ser";

	private static final String TRANSCRIPT_PREFIX = "transcript/";

	private static final String WORKDIR_PREFIX = "workdir/";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SessionArchive() {
	}

	/**
	 * User-supplied metadata stored alongside the session.
	 *
	 * @param name a short human label (may be {@code null})
	 * @param description a longer description (may be {@code null})
	 * @param attributes arbitrary extra metadata; values must be {@link Serializable} so they
	 * can carry live Java objects (e.g. a prompt template + an argument spec). Never
	 * {@code null} — an absent map becomes empty.
	 */
	public record Metadata(String name, String description, Map<String, Serializable> attributes) {

		public Metadata {
			attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
		}

		/** Metadata with just a name and description (no extra attributes). */
		public static Metadata of(String name, String description) {
			return new Metadata(name, description, Map.of());
		}
	}

	/**
	 * The provenance recorded in an archive, readable without extracting it (see
	 * {@link #readManifest}). Attributes are <em>not</em> included here — they require the
	 * attribute classes on the classpath and are read separately via {@link #readAttributes}.
	 *
	 * @param schemaVersion the archive format version
	 * @param sessionId the original (archived) session id
	 * @param originalWorkingDir the absolute working directory the session ran in (the key used
	 * to rewrite paths on restore)
	 * @param createdAt when the archive was written ({@code null} if unparseable)
	 * @param messageCount number of conversation messages in the transcript
	 * @param name the {@link Metadata#name()} (may be {@code null})
	 * @param description the {@link Metadata#description()} (may be {@code null})
	 * @param hasAttributes whether the archive carries serialized attributes
	 */
	public record Manifest(int schemaVersion, String sessionId, String originalWorkingDir, Instant createdAt,
			int messageCount, String name, String description, boolean hasAttributes) {
	}

	/**
	 * The result of a {@link #restore}.
	 *
	 * @param sessionId the restored session id (the original, unless a new one was requested)
	 * @param workingDirectory the directory the working tree was restored into
	 * @param transcriptPath the re-homed transcript file (under the projects root)
	 * @param manifest the archive's manifest
	 */
	public record RestoreResult(String sessionId, Path workingDirectory, Path transcriptPath, Manifest manifest) {
	}

	/**
	 * Archives {@code sessionId} (which ran in {@code workingDir}) to {@code targetArchive},
	 * using the default Claude projects root.
	 * @return the archive file path actually written
	 */
	public static Path create(String sessionId, Path workingDir, Path targetArchive, Metadata metadata)
			throws IOException {
		return create(sessionId, workingDir, targetArchive, metadata, TranscriptDirectory.projectsRoot());
	}

	/**
	 * Archives {@code sessionId} (which ran in {@code workingDir}) to {@code targetArchive}, with
	 * an explicit {@code projectsRoot}.
	 * @return the archive file path actually written (absolute, normalized)
	 * @throws IllegalArgumentException if the working dir/transcript can't be found or the target
	 * archive would sit inside the working tree being captured
	 */
	public static Path create(String sessionId, Path workingDir, Path targetArchive, Metadata metadata,
			Path projectsRoot) throws IOException {
		if (!Files.isDirectory(workingDir)) {
			throw new IllegalArgumentException("workingDir is not a directory: " + workingDir);
		}
		Metadata meta = metadata == null ? Metadata.of(null, null) : metadata;
		Path srcReal = workingDir.toRealPath();
		Path transcript = Transcripts.locateTranscript(projectsRoot, srcReal, sessionId);

		// The archive must live outside the tree we're about to walk, or it would try to
		// capture itself mid-write.
		Path archiveAbs = targetArchive.toAbsolutePath().normalize();
		if (archiveAbs.startsWith(srcReal)) {
			throw new IllegalArgumentException("targetArchive must not be inside workingDir: " + targetArchive);
		}
		if (archiveAbs.getParent() != null) {
			Files.createDirectories(archiveAbs.getParent());
		}

		ObjectNode manifest = buildManifest(sessionId, srcReal.toString(), countMessages(transcript), meta);

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archiveAbs))) {
			writeBytes(zip, MANIFEST, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
			if (!meta.attributes().isEmpty()) {
				writeBytes(zip, ATTRIBUTES, serialize(meta.attributes()));
			}
			writeBytes(zip, TRANSCRIPT_PREFIX + sessionId + ".jsonl", Files.readAllBytes(transcript));
			Path aux = transcript.getParent().resolve(sessionId);
			if (Files.isDirectory(aux)) {
				addTree(zip, aux, TRANSCRIPT_PREFIX + sessionId + "/");
			}
			addTree(zip, srcReal, WORKDIR_PREFIX);
		}
		return archiveAbs;
	}

	/**
	 * Restores an archive into the fresh working directory {@code targetWorkingDir}, keeping the
	 * original session id, using the default projects root.
	 */
	public static RestoreResult restore(Path archive, Path targetWorkingDir) throws IOException {
		return restore(archive, targetWorkingDir, false, TranscriptDirectory.projectsRoot());
	}

	/**
	 * Restores an archive into {@code targetWorkingDir}, optionally minting a new session id
	 * (a "fork on restore"), using the default projects root.
	 */
	public static RestoreResult restore(Path archive, Path targetWorkingDir, boolean newSessionId) throws IOException {
		return restore(archive, targetWorkingDir, newSessionId, TranscriptDirectory.projectsRoot());
	}

	/**
	 * Restores an archive into {@code targetWorkingDir}: inflates the working tree, then re-homes
	 * the transcript under {@code projectsRoot} with every path reference rewritten from the
	 * archived working directory to {@code targetWorkingDir}.
	 * @param newSessionId {@code false} keeps the archived id (a faithful restore/move);
	 * {@code true} assigns a fresh id, forking an independent line on restore
	 * @return the restored id, working directory, transcript path, and the manifest
	 * @throws IllegalArgumentException if the target is non-empty, or (when keeping the id) a
	 * transcript with that id already exists at the destination
	 */
	public static RestoreResult restore(Path archive, Path targetWorkingDir, boolean newSessionId, Path projectsRoot)
			throws IOException {
		if (!Files.isRegularFile(archive)) {
			throw new IllegalArgumentException("archive is not a file: " + archive);
		}
		if (Files.exists(targetWorkingDir) && Transcripts.isNonEmptyDir(targetWorkingDir)) {
			throw new IllegalArgumentException("targetWorkingDir must be empty or non-existent: " + targetWorkingDir);
		}
		Manifest manifest = readManifest(archive);
		if (manifest.sessionId() == null || manifest.originalWorkingDir() == null) {
			throw new IOException("Archive manifest is missing sessionId/originalWorkingDir: " + archive);
		}

		Files.createDirectories(targetWorkingDir);
		Path targetReal = targetWorkingDir.toRealPath();
		String origId = manifest.sessionId();
		String restoreId = newSessionId ? UUID.randomUUID().toString() : origId;

		Path targetProjectsDir = projectsRoot.resolve(TranscriptDirectory.sanitize(targetReal));
		Files.createDirectories(targetProjectsDir);
		Path targetTranscript = targetProjectsDir.resolve(restoreId + ".jsonl");
		if (Files.exists(targetTranscript)) {
			throw new IllegalArgumentException("Target transcript already exists: " + targetTranscript
					+ " (restore into a fresh location, or pass newSessionId=true)");
		}

		String fromPath = manifest.originalWorkingDir();
		String toPath = targetReal.toString();
		Path auxDir = targetProjectsDir.resolve(restoreId);
		String transcriptFileEntry = TRANSCRIPT_PREFIX + origId + ".jsonl";
		String auxPrefix = TRANSCRIPT_PREFIX + origId + "/";

		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				String name = e.getName();
				if (name.equals(MANIFEST) || name.equals(ATTRIBUTES)) {
					continue; // attributes are read on demand via readAttributes(), never auto-deserialized
				}
				if (name.equals(transcriptFileEntry)) {
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
		return new RestoreResult(restoreId, targetWorkingDir, targetTranscript, manifest);
	}

	/**
	 * Reads an archive's {@link Manifest} (name, description, provenance) without extracting it
	 * or touching its serialized attributes.
	 */
	public static Manifest readManifest(Path archive) throws IOException {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
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
					m.path("messageCount").asInt(0), textOrNull(m, "name"), textOrNull(m, "description"),
					m.path("hasAttributes").asBoolean(false));
		}
	}

	/**
	 * Deserializes the archive's attribute map (empty if it has none). Requires the attribute
	 * value classes on the classpath.
	 * @throws IOException if the archive can't be read, or an attribute class is missing
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Serializable> readAttributes(Path archive) throws IOException {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			ZipEntry e = zip.getEntry(ATTRIBUTES);
			if (e == null) {
				return Map.of();
			}
			try (ObjectInputStream ois = new ObjectInputStream(zip.getInputStream(e))) {
				return (Map<String, Serializable>) ois.readObject();
			}
			catch (ClassNotFoundException ex) {
				throw new IOException("An archived attribute's class is not on the classpath: " + ex.getMessage(), ex);
			}
		}
	}

	// --- internals -----------------------------------------------------------------------

	private static ObjectNode buildManifest(String sessionId, String originalWorkingDir, int messageCount,
			Metadata meta) {
		ObjectNode m = MAPPER.createObjectNode();
		m.put("schemaVersion", SCHEMA_VERSION);
		m.put("sessionId", sessionId);
		m.put("originalWorkingDir", originalWorkingDir);
		m.put("createdAt", Instant.now().toString());
		m.put("messageCount", messageCount);
		if (meta.name() != null) {
			m.put("name", meta.name());
		}
		if (meta.description() != null) {
			m.put("description", meta.description());
		}
		m.put("hasAttributes", !meta.attributes().isEmpty());
		return m;
	}

	/** Counts conversation messages (uuid-bearing lines), the same notion as {@code Session.messages()}. */
	private static int countMessages(Path transcript) throws IOException {
		int n = 0;
		for (String line : Files.readAllLines(transcript)) {
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

	private static byte[] serialize(Map<String, Serializable> attributes) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
			oos.writeObject(new HashMap<>(attributes));
		}
		return bos.toByteArray();
	}

	/** Adds every file under {@code root} to the zip, prefixing entry names with {@code entryPrefix}. */
	private static void addTree(ZipOutputStream zip, Path root, String entryPrefix) throws IOException {
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(root)) {
			paths = walk.sorted().toList();
		}
		for (Path p : paths) {
			String rel = root.relativize(p).toString().replace('\\', '/');
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

	private static void extract(ZipFile zip, ZipEntry e, Path dst) throws IOException {
		if (e.isDirectory()) {
			Files.createDirectories(dst);
			return;
		}
		Files.createDirectories(dst.getParent());
		try (InputStream in = zip.getInputStream(e)) {
			Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Resolves {@code rel} under {@code base}, refusing entries that escape it (zip-slip guard). */
	private static Path safeResolve(Path base, String rel) {
		Path baseNorm = base.normalize();
		Path resolved = baseNorm.resolve(rel).normalize();
		if (!resolved.startsWith(baseNorm)) {
			throw new IllegalArgumentException("Illegal archive entry escapes the target directory: " + rel);
		}
		return resolved;
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
