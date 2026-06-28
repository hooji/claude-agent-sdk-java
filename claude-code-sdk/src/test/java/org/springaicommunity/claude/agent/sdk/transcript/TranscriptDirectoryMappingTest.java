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
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the working-directory → transcript-folder mapping
 * ({@link TranscriptDirectory#projectsDirFor}, {@link TranscriptDirectory#forWorkingDirectory})
 * and {@link TranscriptDirectory#mostRecentSession()}.
 *
 * <p>The sanitization rules are verified against the real CLI (v2.1.170): running in
 * {@code /tmp/xprt test/under_score.d} stores transcripts under
 * {@code -tmp-xprt-test-under-score-d}, and running inside a symlinked directory stores
 * them under the canonical (symlink-resolved) path.
 */
class TranscriptDirectoryMappingTest {

	@Test
	void sanitizesEveryNonAlphanumericCharacter() {
		// Observed CLI behavior: '/', '.', '_' and ' ' all become '-'; case is preserved.
		Path workingDir = Path.of("/no-such-root-for-this-test/xprt test/under_score.d");
		String mapped = TranscriptDirectory.projectsDirFor(workingDir.toString(), "/projects");
		assertThat(mapped).isEqualTo("/projects/-no-such-root-for-this-test-xprt-test-under-score-d");

		// The user's UTM example: spaces in a /Volumes path.
		Path utm = Path.of("/Volumes/My Shared Files/shared/claude/test2");
		assertThat(Path.of(TranscriptDirectory.projectsDirFor(utm.toString(), "/p")).getFileName().toString())
				.isEqualTo("-Volumes-My-Shared-Files-shared-claude-test2");
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void resolvesSymlinksToTheCanonicalPath(@TempDir Path tmp) throws Exception {
		Path target = Files.createDirectories(tmp.resolve("real target"));
		Path link = Files.createSymbolicLink(tmp.resolve("link"), target);

		String viaLink = TranscriptDirectory.projectsDirFor(link.toString(), "/p");
		String viaTarget = TranscriptDirectory.projectsDirFor(target.toString(), "/p");

		// The CLI keys storage by the canonical path, so both must map to the same folder.
		assertThat(viaLink).isEqualTo(viaTarget);
		assertThat(Path.of(viaLink).getFileName().toString())
				.isEqualTo(TranscriptDirectory.sanitize(target.toRealPath().toString()));
	}

	@Test
	void missingTranscriptsYieldEmptyDirectory(@TempDir Path tmp) throws Exception {
		Path workingDir = Files.createDirectories(tmp.resolve("fresh"));
		TranscriptDirectory d = TranscriptDirectory.forWorkingDirectory(workingDir.toString(),
				tmp.resolve("projects").toString());

		assertThat(d.sessions()).isEmpty();
		assertThat(d.families()).isEmpty();
		assertThat(d.mostRecentSession()).isNull();
	}

	@Test
	void loadsTranscriptsForWorkingDirectory(@TempDir Path tmp) throws Exception {
		Path workingDir = Files.createDirectories(tmp.resolve("work"));
		Path projectsRoot = tmp.resolve("projects");
		Path transcripts = copyFixturesTo(projectsRoot, workingDir);

		TranscriptDirectory d = TranscriptDirectory.forWorkingDirectory(workingDir.toString(), projectsRoot.toString());
		assertThat(d.directory()).isEqualTo(transcripts.toString());
		assertThat(d.sessions()).hasSize(3);
		assertThat(d.byId(TranscriptDirectoryTest.C)).isPresent();
	}

	@Test
	void mostRecentSessionIsByModificationTime(@TempDir Path tmp) throws Exception {
		Path workingDir = Files.createDirectories(tmp.resolve("work"));
		Path projectsRoot = tmp.resolve("projects");
		Path transcripts = copyFixturesTo(projectsRoot, workingDir);

		Instant base = Instant.parse("2025-06-01T00:00:00Z");
		Files.setLastModifiedTime(transcripts.resolve(TranscriptDirectoryTest.A + ".jsonl"), FileTime.from(base));
		Files.setLastModifiedTime(transcripts.resolve(TranscriptDirectoryTest.C + ".jsonl"),
				FileTime.from(base.plusSeconds(60)));
		Files.setLastModifiedTime(transcripts.resolve(TranscriptDirectoryTest.B + ".jsonl"),
				FileTime.from(base.plusSeconds(120)));

		TranscriptDirectory d = TranscriptDirectory.forWorkingDirectory(workingDir.toString(), projectsRoot.toString());
		assertThat(d.mostRecentSession().sessionId()).isEqualTo(TranscriptDirectoryTest.B);
	}

	/** Copies the fork-lineage fixtures into the projects folder mapped to {@code workingDir}. */
	static Path copyFixturesTo(Path projectsRoot, Path workingDir) throws Exception {
		Path fixtures = Path.of(TranscriptDirectoryMappingTest.class.getResource("/transcripts/fork-lineage").toURI());
		Path transcripts = Files
			.createDirectories(Path.of(TranscriptDirectory.projectsDirFor(workingDir.toString(), projectsRoot.toString())));
		try (var files = Files.list(fixtures)) {
			for (Path f : files.toList()) {
				Files.copy(f, transcripts.resolve(f.getFileName().toString()));
			}
		}
		return transcripts;
	}

}
