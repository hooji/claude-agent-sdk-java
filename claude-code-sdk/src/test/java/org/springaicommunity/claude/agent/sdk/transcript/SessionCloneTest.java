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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCloneTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void clonesFilesAndRehomesTranscript(@TempDir Path source, @TempDir Path target, @TempDir Path projects)
			throws Exception {
		// A file in the source working tree.
		Files.createDirectories(source.resolve("src"));
		Files.writeString(source.resolve("src/Foo.java"), "class Foo {}");
		String srcReal = source.toRealPath().toString();
		String sid = "11111111-1111-1111-1111-111111111111";

		// A fake source transcript (cwd + a tool-result filePath under the source dir).
		Path srcProjDir = projects.resolve(SessionClone.sanitize(source.toRealPath().toString()));
		Files.createDirectories(srcProjDir);
		ObjectNode l1 = mapper.createObjectNode();
		l1.put("type", "user");
		l1.put("sessionId", sid);
		l1.put("cwd", srcReal);
		l1.put("uuid", "u1");
		l1.putObject("message").put("role", "user").put("content", "hi");
		ObjectNode l2 = mapper.createObjectNode();
		l2.put("type", "assistant");
		l2.put("sessionId", sid);
		l2.put("cwd", srcReal);
		l2.put("uuid", "u2");
		l2.putObject("toolUseResult").put("filePath", srcReal + "/src/Foo.java");
		Files.write(srcProjDir.resolve(sid + ".jsonl"),
				List.of(mapper.writeValueAsString(l1), mapper.writeValueAsString(l2)));

		// targetDir provided by @TempDir is empty -> allowed; remove so clone creates it fresh too
		Files.delete(target);

		SessionClone.Result r = SessionClone.clone(sid, source.toString(), target.toString(), projects.toString());

		// 1. the file tree was duplicated
		assertThat(target.resolve("src/Foo.java")).exists();
		assertThat(Files.readString(target.resolve("src/Foo.java"))).isEqualTo("class Foo {}");

		// 2. a new session id + a transcript in the target's projects folder
		assertThat(r.sessionId()).isNotEqualTo(sid);
		assertThat(Path.of(r.transcriptPath())).exists();
		assertThat(r.transcriptPath()).isEqualTo(
				projects.resolve(SessionClone.sanitize(target.toRealPath().toString())).resolve(r.sessionId() + ".jsonl")
					.toString());

		// 3. transcript re-homed: sessionId re-stamped, cwd + filePath rewritten to the target dir
		String tgtReal = target.toRealPath().toString();
		List<String> lines = Files.readAllLines(Path.of(r.transcriptPath()));
		JsonNode c1 = mapper.readTree(lines.get(0));
		assertThat(c1.get("sessionId").asText()).isEqualTo(r.sessionId());
		assertThat(c1.get("cwd").asText()).isEqualTo(tgtReal);
		JsonNode c2 = mapper.readTree(lines.get(1));
		assertThat(c2.get("sessionId").asText()).isEqualTo(r.sessionId());
		assertThat(c2.get("toolUseResult").get("filePath").asText()).isEqualTo(tgtReal + "/src/Foo.java");
	}
}
