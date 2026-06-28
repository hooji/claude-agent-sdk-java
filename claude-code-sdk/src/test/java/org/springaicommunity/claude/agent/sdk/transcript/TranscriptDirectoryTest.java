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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.claude.agent.sdk.types.Message;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link TranscriptDirectory} against captured fork-lineage fixtures:
 * A (4b6f429e) -&gt; B (29efebea) -&gt; C (52d26748), with uuid sets A(6) ⊂ B(14) ⊂ C(23).
 */
class TranscriptDirectoryTest {

	static final String A = "4b6f429e-efe2-459e-8720-56da16280fec";
	static final String B = "29efebea-1d97-4a6c-b39d-207831740ae4";
	static final String C = "52d26748-15ae-4a11-b663-2b6d36195e29";

	Path fixtures() throws Exception {
		return Path.of(getClass().getResource("/transcripts/fork-lineage").toURI());
	}

	@Test
	void loadsAllSessions() throws Exception {
		TranscriptDirectory d = TranscriptDirectory.load(fixtures().toString());
		assertThat(d.sessions()).hasSize(3);
		assertThat(d.byId(A)).isPresent();
		assertThat(d.byId(B)).isPresent();
		assertThat(d.byId(C)).isPresent();
	}

	@Test
	void computesForkPartition() throws Exception {
		TranscriptDirectory d = TranscriptDirectory.load(fixtures().toString());
		Session a = d.byId(A).orElseThrow();
		Session b = d.byId(B).orElseThrow();
		Session c = d.byId(C).orElseThrow();

		assertThat(a.messages()).hasSize(6);
		assertThat(b.messages()).hasSize(14);
		assertThat(c.messages()).hasSize(23);

		// A: single segment (its own); not a fork
		assertThat(a.isFork()).isFalse();
		assertThat(a.segments()).hasSize(1);
		assertThat(a.segments().get(0)).isEqualTo(new ForkSegment(A, 0, 6));

		// B: [A(0,6), B(6,8)]
		assertThat(b.segments()).containsExactly(new ForkSegment(A, 0, 6), new ForkSegment(B, 6, 8));
		assertThat(b.parentSessionId()).isEqualTo(A);

		// C: [A(0,6), B(6,8), C(14,9)]
		assertThat(c.segments()).containsExactly(new ForkSegment(A, 0, 6), new ForkSegment(B, 6, 8),
				new ForkSegment(C, 14, 9));
		assertThat(c.parentSessionId()).isEqualTo(B);
		assertThat(c.forkPointIndex()).isEqualTo(14);

		// segments tile the message list with no gaps/overlaps
		assertTiling(c);
	}

	@Test
	void buildsConversationFamilyTree() throws Exception {
		TranscriptDirectory d = TranscriptDirectory.load(fixtures().toString());
		assertThat(d.families()).hasSize(1);
		ConversationFamily fam = d.families().get(0);
		assertThat(fam.rootSessionId()).isEqualTo(A);

		ForkNode root = fam.tree();
		assertThat(root.sessionId()).isEqualTo(A);
		assertThat(root.children()).hasSize(1);

		ForkNode bNode = root.children().get(0);
		assertThat(bNode.sessionId()).isEqualTo(B);
		assertThat(bNode.forkPointInParent()).isEqualTo(6); // B forked from A after A's 6 messages
		assertThat(bNode.children()).hasSize(1);

		ForkNode cNode = bNode.children().get(0);
		assertThat(cNode.sessionId()).isEqualTo(C);
		assertThat(cNode.forkPointInParent()).isEqualTo(14); // C forked from B after B's 14 messages
		assertThat(cNode.children()).isEmpty();
	}

	@Test
	void markdownDescribesStructure() throws Exception {
		String md = TranscriptDirectory.load(fixtures().toString()).toMarkdown();
		assertThat(md).contains("independent conversations: 1");
		assertThat(md).contains("original");
		assertThat(md).contains("forked from");
	}

	@Test
	void regeneratesJsonEquivalent(@TempDir Path tmp) throws Exception {
		Path src = fixtures();
		TranscriptDirectory d = TranscriptDirectory.load(src.toString());
		d.regenerate(tmp.toString());

		ObjectMapper m = new ObjectMapper();
		for (String name : List.of(A + ".jsonl", B + ".jsonl", C + ".jsonl")) {
			List<JsonNode> original = jsonLines(m, src.resolve(name));
			List<JsonNode> regenerated = jsonLines(m, tmp.resolve(name));
			assertThat(regenerated)
					.as("round-trip JSON equivalence for " + name)
					.isEqualTo(original);
		}
	}

	@Test
	void replayEmitsForkMarkersAndHistoryEnd() throws Exception {
		TranscriptDirectory d = TranscriptDirectory.load(fixtures().toString());
		List<Message> replay = d.replayMessages(C);

		List<ForkMarker> markers = replay.stream()
				.filter(ForkMarker.class::isInstance)
				.map(ForkMarker.class::cast)
				.toList();
		assertThat(markers).hasSize(2);
		assertThat(markers.get(0).parentSessionId()).isEqualTo(A);
		assertThat(markers.get(0).childSessionId()).isEqualTo(B);
		assertThat(markers.get(0).messageIndex()).isEqualTo(6);
		assertThat(markers.get(1).parentSessionId()).isEqualTo(B);
		assertThat(markers.get(1).childSessionId()).isEqualTo(C);
		assertThat(markers.get(1).messageIndex()).isEqualTo(14);

		// terminal HistoryEnd, with the leaf's message count
		Message last = replay.get(replay.size() - 1);
		assertThat(last).isInstanceOf(HistoryEnd.class);
		assertThat(((HistoryEnd) last).sessionId()).isEqualTo(C);
		assertThat(((HistoryEnd) last).messageCount()).isEqualTo(23);
	}

	@Test
	void replayContentFallsInTheRightBranch() throws Exception {
		TranscriptDirectory d = TranscriptDirectory.load(fixtures().toString());
		List<Message> replay = d.replayMessages(C);

		// Concatenate text per branch (split at the fork markers).
		StringBuilder root = new StringBuilder();
		StringBuilder branchB = new StringBuilder();
		StringBuilder branchC = new StringBuilder();
		StringBuilder target = root;
		for (Message m : replay) {
			if (m instanceof ForkMarker fm) {
				target = fm.childSessionId().equals(B) ? branchB : branchC;
				continue;
			}
			target.append(m.toString()).append('\n');
		}
		// A established AARDVARK; B added 42; C added OTTER.
		assertThat(root.toString()).contains("AARDVARK");
		assertThat(branchB.toString()).contains("42");
		assertThat(branchC.toString()).contains("OTTER");
	}

	@Test
	void replayEmitsEveryEntryNothingDropped() throws Exception {
		TranscriptDirectory d = TranscriptDirectory.load(fixtures().toString());
		Session c = d.byId(C).orElseThrow();
		List<Message> replay = d.replayMessages(C);

		long markers = replay.stream().filter(m -> m.getType().equals("fork_marker")).count();
		long ends = replay.stream().filter(m -> m.getType().equals("history_end")).count();
		long emittedEntries = replay.size() - markers - ends;

		// every line of the file is represented (none dropped)
		assertThat(emittedEntries).isEqualTo(c.entries().size());

		// non-conversation lines are now emitted (as RawTranscriptMessage) with their real type
		assertThat(replay.stream().map(Message::getType).distinct())
				.contains("user", "assistant", "attachment", "queue-operation", "mode", "last-prompt");
	}

	@Test
	void sessionReplaysItself() throws Exception {
		TranscriptDirectory d = TranscriptDirectory.load(fixtures().toString());
		Session c = d.byId(C).orElseThrow();

		// replay lives on the Session; the directory method is a delegating convenience
		assertThat(c.replayMessages()).isEqualTo(d.replayMessages(C));

		// the reactive form emits the same sequence
		assertThat(c.replay().collectList().block()).isEqualTo(c.replayMessages());

		// fork markers (with sibling navigation) are precomputed per segment boundary
		assertThat(c.forkMarkers()).hasSize(c.segments().size() - 1);
	}

	@Test
	void extractsReferencedFilePaths() throws Exception {
		// Shapes observed in real transcripts: an edited_text_file attachment (filename) and
		// a tool file operation (filePath).
		ObjectMapper m = new ObjectMapper();
		JsonNode raw = m.readTree("{\"type\":\"attachment\",\"attachment\":{\"type\":\"edited_text_file\","
				+ "\"filename\":\"/Users/nat/proj/Foo.java\",\"snippet\":\"...\"},"
				+ "\"toolUseResult\":{\"file\":{\"filePath\":\"/Users/nat/proj/Bar.java\"}},"
				+ "\"note\":\"not a path\"}");
		TranscriptEntry entry = new TranscriptEntry(1, null, null, "attachment", false, null, null, null, raw);

		assertThat(entry.referencedFiles())
				.containsExactlyInAnyOrder("/Users/nat/proj/Foo.java", "/Users/nat/proj/Bar.java");

		// RawTranscriptMessage (what replay emits) exposes the same convenience.
		RawTranscriptMessage raw2 = new RawTranscriptMessage("attachment", null, raw);
		assertThat(raw2.referencedFiles()).hasSize(2);
	}

	private static List<JsonNode> jsonLines(ObjectMapper m, Path file) throws Exception {
		return Files.readAllLines(file).stream()
				.filter(l -> !l.isBlank())
				.map(l -> {
					try {
						return m.readTree(l);
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}
				})
				.toList();
	}

	private static void assertTiling(Session s) {
		int expected = 0;
		for (ForkSegment seg : s.segments()) {
			assertThat(seg.startIndex()).isEqualTo(expected);
			expected += seg.count();
		}
		assertThat(expected).isEqualTo(s.messages().size());
	}
}
