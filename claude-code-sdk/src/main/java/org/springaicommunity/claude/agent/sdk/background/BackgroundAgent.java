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

package org.springaicommunity.claude.agent.sdk.background;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import org.springaicommunity.claude.agent.sdk.transcript.Session;
import org.springaicommunity.claude.agent.sdk.transcript.SessionArchive;
import org.springaicommunity.claude.agent.sdk.transcript.TranscriptDirectory;
import org.springaicommunity.claude.agent.sdk.transcript.TranscriptEntry;

/**
 * A handle to a dispatched (or discovered) Claude Code background agent. Obtain one from
 * {@link BackgroundAgents#dispatch(String) dispatch}, {@link BackgroundAgents#list() list}, or
 * {@link BackgroundAgents#get(String) get}.
 *
 * <p>The handle's identity ({@link #id()}, {@link #sessionId()}, {@link #workingDirectory()}) is
 * fixed; its {@linkplain #status() status} and {@linkplain #result() result} are read live from
 * the supervisor and the on-disk transcript respectively.
 */
public final class BackgroundAgent {

	private final String id;

	private final String sessionId;

	private final String workingDirectory;

	BackgroundAgent(String id, String sessionId, String workingDirectory) {
		this.id = id;
		this.sessionId = sessionId;
		this.workingDirectory = workingDirectory;
	}

	/** @return the short id (used by {@code claude logs}/{@code stop}). */
	public String id() {
		return id;
	}

	/** @return the full session id (the transcript filename), or {@code null} if unknown. */
	public String sessionId() {
		return sessionId;
	}

	/** @return the working directory the agent runs in. */
	public String workingDirectory() {
		return workingDirectory;
	}

	/** @return the agent's current supervisor snapshot, or empty if it is no longer known. */
	public Optional<BackgroundAgentStatus> status() throws IOException {
		return BackgroundAgents.status(cliId());
	}

	/** @return the agent's current {@link BackgroundAgentState} (UNKNOWN if it is gone). */
	public BackgroundAgentState state() throws IOException {
		return status().map(BackgroundAgentStatus::state).orElse(BackgroundAgentState.UNKNOWN);
	}

	/** {@link #awaitTerminal(Duration, Duration)} polling every 2 seconds. */
	public BackgroundAgentStatus awaitTerminal(Duration timeout)
			throws IOException, InterruptedException, TimeoutException {
		return awaitTerminal(timeout, Duration.ofSeconds(2));
	}

	/**
	 * Polls until the agent reaches a {@linkplain BackgroundAgentState#isTerminal() terminal}
	 * state or {@code timeout} elapses.
	 * @param timeout the maximum time to wait
	 * @param pollInterval how long to sleep between {@code claude agents --json} polls
	 * @return the terminal snapshot
	 * @throws TimeoutException if no terminal state is reached within {@code timeout}
	 */
	public BackgroundAgentStatus awaitTerminal(Duration timeout, Duration pollInterval)
			throws IOException, InterruptedException, TimeoutException {
		long deadlineNanos = System.nanoTime() + timeout.toNanos();
		long sleepMillis = Math.max(1, pollInterval.toMillis());
		while (true) {
			BackgroundAgentStatus s = status().orElse(null);
			if (s != null && s.state().isTerminal()) {
				return s;
			}
			if (System.nanoTime() >= deadlineNanos) {
				throw new TimeoutException("Background agent " + cliId() + " did not reach a terminal state within "
						+ timeout + " (last state: " + (s != null ? s.state() : "gone") + ")");
			}
			Thread.sleep(sleepMillis);
		}
	}

	/**
	 * @return the agent's recent terminal output via {@code claude logs} (raw, ANSI-laden).
	 * Prefer {@link #transcript()}/{@link #result()} for structured access.
	 */
	public String logs() throws IOException {
		return BackgroundAgents.logs(cliId());
	}

	/** Stops the agent ({@code claude stop}); its conversation is kept. */
	public void stop() throws IOException {
		BackgroundAgents.stop(cliId());
	}

	/**
	 * Loads the agent's conversation transcript from disk via the SDK's transcript toolkit
	 * (so it composes with replay and {@link SessionArchive}).
	 * @return the {@link Session}, or empty if it has not been written yet
	 */
	public Optional<Session> transcript() throws IOException {
		if (sessionId == null || workingDirectory == null) {
			return Optional.empty();
		}
		return TranscriptDirectory.forWorkingDirectory(workingDirectory).byId(sessionId);
	}

	/**
	 * @return the agent's final answer — the text of the last assistant message in its
	 * transcript — or empty if there is none yet.
	 */
	public Optional<String> result() throws IOException {
		return transcript().flatMap(BackgroundAgent::lastAssistantText);
	}

	/**
	 * Archives this agent's session (transcript, its {@code .meta} metadata, and its entire
	 * working directory tree) to a single portable file (see {@link SessionArchive}). The metadata
	 * is picked up from the session's {@code <id>.meta} sidecar, if any.
	 * @param targetArchive the archive file to write
	 * @return the archive file written
	 * @throws IllegalStateException if the agent's session id is not known
	 */
	public String archiveTo(String targetArchive) throws IOException {
		if (sessionId == null) {
			throw new IllegalStateException("Cannot archive a background agent whose session id is unknown");
		}
		return SessionArchive.create(sessionId, workingDirectory, targetArchive);
	}

	private String cliId() {
		return id != null ? id : sessionId;
	}

	/** Extracts the text of the last assistant message in the session. */
	private static Optional<String> lastAssistantText(Session session) {
		String last = null;
		for (TranscriptEntry e : session.entries()) {
			if (!"assistant".equals(e.type())) {
				continue;
			}
			JsonNode content = e.raw().path("message").path("content");
			if (content.isArray()) {
				StringBuilder sb = new StringBuilder();
				for (JsonNode block : content) {
					if ("text".equals(block.path("type").asText())) {
						if (sb.length() > 0) {
							sb.append('\n');
						}
						sb.append(block.path("text").asText());
					}
				}
				if (sb.length() > 0) {
					last = sb.toString();
				}
			}
		}
		return Optional.ofNullable(last);
	}

}
