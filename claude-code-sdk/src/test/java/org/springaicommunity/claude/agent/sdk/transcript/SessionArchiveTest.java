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

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.claude.agent.sdk.TranscriptAware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionArchiveTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final String sid = "11111111-1111-1111-1111-111111111111";

	/** Builds a fake working tree + transcript (with a sidecar tool-result) for the default id. */
	private void seedSession(Path source, Path projects) throws Exception {
		seedSession(source, projects, sid);
	}

	/** As {@link #seedSession(Path, Path)} but for an explicit {@code sessionId}. */
	private void seedSession(Path source, Path projects, String sessionId) throws Exception {
		Files.createDirectories(source.resolve("src"));
		Files.writeString(source.resolve("src/Foo.java"), "class Foo {}");
		String srcReal = source.toRealPath().toString();

		Path srcProjDir = projects.resolve(TranscriptDirectory.sanitize(source.toRealPath()));
		Files.createDirectories(srcProjDir);
		ObjectNode l1 = mapper.createObjectNode();
		l1.put("type", "user");
		l1.put("sessionId", sessionId);
		l1.put("cwd", srcReal);
		l1.put("uuid", "u1");
		l1.putObject("message").put("role", "user").put("content", "hi");
		ObjectNode l2 = mapper.createObjectNode();
		l2.put("type", "assistant");
		l2.put("sessionId", sessionId);
		l2.put("cwd", srcReal);
		l2.put("uuid", "u2");
		l2.putObject("toolUseResult").put("filePath", srcReal + "/src/Foo.java");
		Files.write(srcProjDir.resolve(sessionId + ".jsonl"),
				List.of(mapper.writeValueAsString(l1), mapper.writeValueAsString(l2)));

		// an externalized tool-result sidecar file
		Files.createDirectories(srcProjDir.resolve(sessionId));
		Files.writeString(srcProjDir.resolve(sessionId).resolve("big-result.txt"), "lots of output");
	}

	@Test
	void archivesPeeksAndRestoresKeepingTheId(@TempDir Path source, @TempDir Path projects, @TempDir Path tmp)
			throws Exception {
		seedSession(source, projects);

		// Attributes carry live Java objects (a prompt template + an argument spec).
		Map<String, Serializable> attrs = new HashMap<>();
		attrs.put("promptTemplate", "Do {{task}} with {{args}}");
		attrs.put("argSpec", new ArrayList<>(List.of("task", "args")));
		SessionArchive.Metadata meta = new SessionArchive.Metadata("My App", "A primed session", attrs);

		Path archive = tmp.resolve("backup.ccsession.zip");
		Path written = SessionArchive.create(sid, source, archive, meta, projects);
		assertThat(written).exists().isEqualTo(archive.toAbsolutePath().normalize());

		// Peek the manifest without extracting.
		SessionArchive.Manifest man = SessionArchive.readManifest(archive);
		assertThat(man.sessionId()).isEqualTo(sid);
		assertThat(man.originalWorkingDir()).isEqualTo(source.toRealPath().toString());
		assertThat(man.name()).isEqualTo("My App");
		assertThat(man.description()).isEqualTo("A primed session");
		assertThat(man.messageCount()).isEqualTo(2);
		assertThat(man.hasAttributes()).isTrue();
		assertThat(man.createdAt()).isNotNull();

		// Attributes round-trip as live objects.
		Map<String, Serializable> back = SessionArchive.readAttributes(archive);
		assertThat(back).containsEntry("promptTemplate", "Do {{task}} with {{args}}");
		assertThat(back.get("argSpec")).isEqualTo(List.of("task", "args"));

		// Restore (keep id) into a fresh directory.
		Path target = tmp.resolve("restored");
		SessionArchive.RestoreResult r = SessionArchive.restore(archive, target, false, projects);
		String tgtReal = target.toRealPath().toString();

		assertThat(r.sessionId()).isEqualTo(sid);
		assertThat(target.resolve("src/Foo.java")).exists();
		assertThat(Files.readString(target.resolve("src/Foo.java"))).isEqualTo("class Foo {}");

		// Transcript re-homed under the target's projects folder, paths rewritten, id kept.
		Path tgtProjDir = projects.resolve(TranscriptDirectory.sanitize(target.toRealPath()));
		assertThat(r.transcriptPath()).isEqualTo(tgtProjDir.resolve(sid + ".jsonl"));
		List<String> lines = Files.readAllLines(r.transcriptPath());
		JsonNode c1 = mapper.readTree(lines.get(0));
		assertThat(c1.get("sessionId").asText()).isEqualTo(sid);
		assertThat(c1.get("cwd").asText()).isEqualTo(tgtReal);
		JsonNode c2 = mapper.readTree(lines.get(1));
		assertThat(c2.get("toolUseResult").get("filePath").asText()).isEqualTo(tgtReal + "/src/Foo.java");

		// Sidecar tool-result restored under the (kept) id.
		assertThat(tgtProjDir.resolve(sid).resolve("big-result.txt")).exists();
		assertThat(Files.readString(tgtProjDir.resolve(sid).resolve("big-result.txt"))).isEqualTo("lots of output");
	}

	@Test
	void restoreCanMintANewId(@TempDir Path source, @TempDir Path projects, @TempDir Path tmp) throws Exception {
		seedSession(source, projects);
		Path archive = tmp.resolve("backup.zip");
		SessionArchive.create(sid, source, archive, SessionArchive.Metadata.of("n", "d"), projects);

		Path target = tmp.resolve("forked");
		SessionArchive.RestoreResult r = SessionArchive.restore(archive, target, true, projects);

		assertThat(r.sessionId()).isNotEqualTo(sid);
		assertThat(r.transcriptPath().getFileName().toString()).isEqualTo(r.sessionId() + ".jsonl");
		JsonNode first = mapper.readTree(Files.readAllLines(r.transcriptPath()).get(0));
		assertThat(first.get("sessionId").asText()).isEqualTo(r.sessionId());
		assertThat(first.get("cwd").asText()).isEqualTo(target.toRealPath().toString());
	}

	@Test
	void archiveWithNoAttributesHasNoneToRead(@TempDir Path source, @TempDir Path projects, @TempDir Path tmp)
			throws Exception {
		seedSession(source, projects);
		Path archive = tmp.resolve("plain.zip");
		SessionArchive.create(sid, source, archive, SessionArchive.Metadata.of("n", null), projects);

		assertThat(SessionArchive.readManifest(archive).hasAttributes()).isFalse();
		assertThat(SessionArchive.readAttributes(archive)).isEmpty();
	}

	@Test
	void refusesToArchiveIntoTheWorkingTree(@TempDir Path source, @TempDir Path projects) throws Exception {
		seedSession(source, projects);
		Path inside = source.resolve("backup.zip");
		assertThatThrownBy(() -> SessionArchive.create(sid, source, inside, SessionArchive.Metadata.of("n", "d"),
				projects))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must not be inside");
	}

	@Test
	void refusesToRestoreOntoANonEmptyTarget(@TempDir Path source, @TempDir Path projects, @TempDir Path tmp)
			throws Exception {
		seedSession(source, projects);
		Path archive = tmp.resolve("b.zip");
		SessionArchive.create(sid, source, archive, SessionArchive.Metadata.of("n", "d"), projects);

		Path target = tmp.resolve("occupied");
		Files.createDirectories(target);
		Files.writeString(target.resolve("existing.txt"), "x");
		assertThatThrownBy(() -> SessionArchive.restore(archive, target, false, projects))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be empty");
	}

	@Test
	void archivesViaSessionConvenience(@TempDir Path source, @TempDir Path projects, @TempDir Path tmp)
			throws Exception {
		seedSession(source, projects);
		Session session = TranscriptDirectory.forWorkingDirectory(source, projects).byId(sid).orElseThrow();

		// The working directory is recovered from the transcript's cwd.
		assertThat(session.workingDirectory()).contains(source.toRealPath());

		Path archive = tmp.resolve("via-session.zip");
		session.archiveTo(archive, SessionArchive.Metadata.of("S", "via session"));
		assertThat(SessionArchive.readManifest(archive).sessionId()).isEqualTo(sid);

		Path target = tmp.resolve("restored");
		SessionArchive.RestoreResult r = SessionArchive.restore(archive, target, false, projects);
		assertThat(r.sessionId()).isEqualTo(sid);
		assertThat(target.resolve("src/Foo.java")).exists();
	}

	@Test
	void discoversEverySessionUnderTheProjectsRoot(@TempDir Path sourceA, @TempDir Path sourceB,
			@TempDir Path projects) throws Exception {
		String idB = "22222222-2222-2222-2222-222222222222";
		seedSession(sourceA, projects, sid);
		seedSession(sourceB, projects, idB);

		List<TranscriptDirectory> all = TranscriptDirectory.allUnder(projects);

		assertThat(all).hasSize(2);
		List<String> ids = all.stream()
			.flatMap(d -> d.sessions().stream())
			.map(Session::sessionId)
			.sorted()
			.toList();
		assertThat(ids).containsExactly(sid, idB);
	}

	@Test
	void clientArchivesItsCurrentSession(@TempDir Path source, @TempDir Path config, @TempDir Path tmp)
			throws Exception {
		// archiveSession() resolves against the default projects root, so point it at a temp one.
		Path projects = config.resolve("projects");
		seedSession(source, projects);

		String prev = System.getProperty("claude.config.dir");
		System.setProperty("claude.config.dir", config.toString());
		try {
			TranscriptAware client = new TranscriptAware() {
				@Override
				public Path getWorkingDirectory() {
					return source;
				}

				@Override
				public String getCurrentSessionId() {
					return sid;
				}
			};

			Path archive = client.archiveSession(tmp.resolve("client.zip"),
					SessionArchive.Metadata.of("client", "now"));

			assertThat(archive).exists();
			SessionArchive.Manifest m = SessionArchive.readManifest(archive);
			assertThat(m.sessionId()).isEqualTo(sid);
			assertThat(m.originalWorkingDir()).isEqualTo(source.toRealPath().toString());
			assertThat(m.name()).isEqualTo("client");
		}
		finally {
			if (prev == null) {
				System.clearProperty("claude.config.dir");
			}
			else {
				System.setProperty("claude.config.dir", prev);
			}
		}
	}

}
