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

package org.springaicommunity.claude.agent.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.claude.agent.sdk.transcript.Session;
import org.springaicommunity.claude.agent.sdk.transcript.TranscriptDirectory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@link TranscriptAware} default methods against the fork-lineage fixtures,
 * redirecting the projects root via the {@code claude.config.dir} system property.
 */
class TranscriptAwareTest {

	static final String A = "4b6f429e-efe2-459e-8720-56da16280fec";
	static final String B = "29efebea-1d97-4a6c-b39d-207831740ae4";
	static final String C = "52d26748-15ae-4a11-b663-2b6d36195e29";

	@TempDir
	Path tmp;

	Path workingDir;

	@BeforeEach
	void redirectProjectsRoot() throws Exception {
		System.setProperty("claude.config.dir", tmp.resolve("claude-config").toString());
		workingDir = Files.createDirectories(tmp.resolve("work"));
	}

	@AfterEach
	void restoreProjectsRoot() {
		System.clearProperty("claude.config.dir");
	}

	/** A client stub: the two abstract methods, everything else from the interface. */
	private TranscriptAware client(String currentSessionId) {
		return new TranscriptAware() {
			@Override
			public String getWorkingDirectory() {
				return workingDir.toString();
			}

			@Override
			public String getCurrentSessionId() {
				return currentSessionId;
			}
		};
	}

	@Test
	void emptyWorkingDirectoryHasNoSessions() {
		TranscriptAware client = client(null);
		assertThat(client.getTranscriptDirectory().sessions()).isEmpty();
		assertThat(client.getSession()).isNull();
		assertThat(client.getSession("no-such-id")).isNull();
	}

	@Test
	void getSessionByIdLoadsTheTranscript() throws Exception {
		copyFixtures();
		TranscriptAware client = client(null);

		Session b = client.getSession(B);
		assertThat(b).isNotNull();
		assertThat(b.sessionId()).isEqualTo(B);
		assertThat(b.messages()).hasSize(14);
		assertThat(client.getSession("no-such-id")).isNull();
	}

	@Test
	void noArgGetSessionUsesTheCurrentSessionId() throws Exception {
		copyFixtures();
		assertThat(client(C).getSession().sessionId()).isEqualTo(C);
	}

	@Test
	void noArgGetSessionFallsBackToMostRecent() throws Exception {
		Path transcripts = copyFixtures();
		Instant base = Instant.parse("2025-06-01T00:00:00Z");
		Files.setLastModifiedTime(transcripts.resolve(B + ".jsonl"), FileTime.from(base));
		Files.setLastModifiedTime(transcripts.resolve(C + ".jsonl"), FileTime.from(base.plusSeconds(30)));
		Files.setLastModifiedTime(transcripts.resolve(A + ".jsonl"), FileTime.from(base.plusSeconds(60)));

		// No current id at all (e.g. continueConversation(true) before connecting).
		assertThat(client(null).getSession().sessionId()).isEqualTo(A);

		// Current id known but its transcript missing: also falls back to most recent.
		assertThat(client("11111111-2222-3333-4444-555555555555").getSession().sessionId()).isEqualTo(A);
	}

	private Path copyFixtures() throws Exception {
		Path fixtures = Path.of(getClass().getResource("/transcripts/fork-lineage").toURI());
		Path transcripts = Files.createDirectories(Path.of(TranscriptDirectory.projectsDirFor(workingDir.toString())));
		try (var files = Files.list(fixtures)) {
			for (Path f : files.toList()) {
				Files.copy(f, transcripts.resolve(f.getFileName().toString()));
			}
		}
		return transcripts;
	}

}
