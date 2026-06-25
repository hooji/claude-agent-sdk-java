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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The single source of truth for the SDK's per-session metadata sidecar: a Java-serialized
 * {@code Map<String, Serializable>} stored next to a session's {@code <id>.jsonl} transcript as
 * {@code <id>.meta}, inside the same Claude Code projects folder.
 *
 * <p>This is an SDK convention: Claude Code itself neither writes nor reads {@code .meta} files,
 * and (the loader only looks at {@code *.jsonl}) leaves them undisturbed. Everything that needs
 * to read, write, locate, or compare a {@code .meta} file goes through here so the on-disk
 * format and naming never drift between {@link Session}, {@link TranscriptDirectory} and
 * {@link SessionArchive}.
 *
 * <p>The map is serialized with {@link java.io.LinkedHashMap} so iteration order is preserved
 * across a round trip. Values are stored with Java serialization, so reading a {@code .meta}
 * file (or an archived copy) requires the value classes on the classpath; reading an untrusted
 * file carries the usual Java-deserialization risk and any failure is allowed to propagate.
 */
final class SessionMetadata {

	/** The sidecar extension, replacing {@code .jsonl} on the transcript's filename. */
	static final String EXTENSION = ".meta";

	private SessionMetadata() {
	}

	/** @return the {@code <id>.meta} sidecar path for a {@code <id>.jsonl} transcript file. */
	static Path fileFor(Path transcriptFile) {
		String name = transcriptFile.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String base = dot < 0 ? name : name.substring(0, dot);
		return transcriptFile.resolveSibling(base + EXTENSION);
	}

	/**
	 * Reads the metadata map from {@code metaFile}, returning a fresh empty (mutable, insertion
	 * ordered) map if the file does not exist.
	 * @throws IOException if the file exists but cannot be read or deserialized (e.g. a value's
	 * class is missing) — never swallowed
	 */
	static Map<String, Serializable> readFromFile(Path metaFile) throws IOException {
		if (!Files.isRegularFile(metaFile)) {
			return new LinkedHashMap<>();
		}
		return deserialize(Files.readAllBytes(metaFile));
	}

	/** Serializes {@code map} and writes it to {@code metaFile} (an empty map writes an empty-map file). */
	static void writeToFile(Path metaFile, Map<String, Serializable> map) throws IOException {
		Files.write(metaFile, serialize(map));
	}

	/** Serializes {@code map} as a {@link LinkedHashMap} (preserving iteration order). */
	static byte[] serialize(Map<String, Serializable> map) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
			oos.writeObject(new LinkedHashMap<>(map));
		}
		return bos.toByteArray();
	}

	/** Deserializes bytes written by {@link #serialize} into a fresh mutable, insertion-ordered map. */
	@SuppressWarnings("unchecked")
	static Map<String, Serializable> deserialize(byte[] bytes) throws IOException {
		try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
			return new LinkedHashMap<>((Map<String, Serializable>) ois.readObject());
		}
		catch (ClassNotFoundException ex) {
			throw new IOException("A session metadata value's class is not on the classpath: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Order-sensitive map equality: same size, same key/value pairs, in the same iteration order.
	 * Used to verify a {@link Session}'s in-memory metadata still matches its {@code .meta} file
	 * (a plain {@link Map#equals} would ignore the {@code LinkedHashMap} ordering). Values are
	 * compared with {@link Objects#equals}, so it relies on each value's own {@code equals}.
	 */
	static boolean equalsOrdered(Map<String, Serializable> a, Map<String, Serializable> b) {
		if (a == b) {
			return true;
		}
		if (a.size() != b.size()) {
			return false;
		}
		Iterator<Map.Entry<String, Serializable>> ia = a.entrySet().iterator();
		Iterator<Map.Entry<String, Serializable>> ib = b.entrySet().iterator();
		while (ia.hasNext()) {
			Map.Entry<String, Serializable> ea = ia.next();
			Map.Entry<String, Serializable> eb = ib.next();
			if (!Objects.equals(ea.getKey(), eb.getKey()) || !Objects.equals(ea.getValue(), eb.getValue())) {
				return false;
			}
		}
		return true;
	}

}
