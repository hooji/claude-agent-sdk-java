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

import java.io.IOException;
import java.nio.file.Path;

import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException;
import org.springaicommunity.claude.agent.sdk.transcript.Session;
import org.springaicommunity.claude.agent.sdk.transcript.SessionArchive;
import org.springaicommunity.claude.agent.sdk.transcript.TranscriptDirectory;

/**
 * Unified access to the on-disk conversation history (transcripts) associated with a
 * client's working directory. Both {@link ClaudeSyncClient} and {@link ClaudeAsyncClient}
 * extend this interface, so a user can open a client and immediately retrieve the history
 * of the sessions that ran in its directory:
 *
 * <pre>{@code
 * try (ClaudeSyncClient client = ClaudeClient.sync()
 *         .workingDirectory(Path.of("/path/the/user/sees"))
 *         .build()) {
 *     client.connectText("Hello");
 *     Session session = client.getSession();           // this session's transcript
 *     TranscriptDirectory all = client.getTranscriptDirectory(); // every session here
 * }
 * }</pre>
 *
 * <p>
 * Callers supply the working directory the session is <em>executed</em> in (what the user
 * sees); the mapping to the actual storage folder under the Claude projects root —
 * including symlink canonicalization and path sanitization — is handled by
 * {@link TranscriptDirectory#projectsDirFor(Path)}.
 * </p>
 *
 * <p>
 * The returned objects are point-in-time snapshots read from disk on each call: a live
 * session's transcript grows as the conversation progresses, so re-invoke these methods to
 * observe new messages.
 * </p>
 */
public interface TranscriptAware {

	/**
	 * The working directory the Claude session runs in, as configured on the client (the
	 * directory the user sees, before any symlink resolution).
	 * @return the working directory, or {@code null} if not configured (the CLI then runs
	 * in the JVM's current directory)
	 */
	Path getWorkingDirectory();

	/**
	 * The id of the session this client is currently talking to, as observed on the wire
	 * (the CLI reports it on system and result messages).
	 * @return the current session id, or {@code null} if not yet known — e.g. before the
	 * first response has arrived, including when the client was built with
	 * {@code continueConversation(true)} or {@code resume(...)} but has not connected yet
	 */
	String getCurrentSessionId();

	/**
	 * Loads the transcripts of every session associated with this client's working
	 * directory (see {@link #getWorkingDirectory()}; the JVM's current directory is used
	 * when none is configured).
	 * @return the loaded transcript directory; empty if no sessions have run there yet
	 * @throws ClaudeSDKException if the transcripts cannot be read
	 */
	default TranscriptDirectory getTranscriptDirectory() {
		Path workingDirectory = getWorkingDirectory();
		if (workingDirectory == null) {
			workingDirectory = Path.of(System.getProperty("user.dir"));
		}
		try {
			return TranscriptDirectory.forWorkingDirectory(workingDirectory);
		}
		catch (IOException e) {
			throw new ClaudeSDKException("Failed to load transcripts for working directory " + workingDirectory, e);
		}
	}

	/**
	 * Loads the transcript of one session associated with this client's working directory.
	 * @param sessionId the session id
	 * @return the session's transcript, or {@code null} if no such session exists
	 * @throws ClaudeSDKException if the transcripts cannot be read
	 */
	default Session getSession(String sessionId) {
		return getTranscriptDirectory().byId(sessionId).orElse(null);
	}

	/**
	 * Loads the transcript of this client's session: the session identified by
	 * {@link #getCurrentSessionId()} when known, otherwise the most recently modified
	 * session in the working directory (the same session the CLI's {@code --continue}
	 * would resume).
	 * @return the session's transcript, or {@code null} if the directory has no sessions
	 * (or the current session's transcript has not been written yet)
	 * @throws ClaudeSDKException if the transcripts cannot be read
	 */
	default Session getSession() {
		TranscriptDirectory transcripts = getTranscriptDirectory();
		String sessionId = getCurrentSessionId();
		if (sessionId != null) {
			Session session = transcripts.byId(sessionId).orElse(null);
			if (session != null) {
				return session;
			}
		}
		return transcripts.mostRecentSession();
	}

	/**
	 * Archives one session of this client's working directory to a single portable file (see
	 * {@link SessionArchive}). Uses this client's configured {@link #getWorkingDirectory()} (the
	 * JVM's current directory when none is set) and the default Claude projects root.
	 * @param sessionId the session to archive
	 * @param targetArchive the archive file to write
	 * @param metadata name/description/attributes to embed (may be {@code null})
	 * @return the archive file written
	 * @throws ClaudeSDKException if the session's files can't be read or the archive can't be
	 * written
	 */
	default Path archiveSession(String sessionId, Path targetArchive, SessionArchive.Metadata metadata) {
		Path workingDirectory = getWorkingDirectory();
		if (workingDirectory == null) {
			workingDirectory = Path.of(System.getProperty("user.dir"));
		}
		try {
			return SessionArchive.create(sessionId, workingDirectory, targetArchive, metadata);
		}
		catch (IOException e) {
			throw new ClaudeSDKException("Failed to archive session " + sessionId + " to " + targetArchive, e);
		}
	}

	/**
	 * Archives this client's current session (see {@link #getSession()}) to a single portable
	 * file.
	 * @param targetArchive the archive file to write
	 * @param metadata name/description/attributes to embed (may be {@code null})
	 * @return the archive file written
	 * @throws ClaudeSDKException if there is no session yet, or the archive can't be written
	 */
	default Path archiveSession(Path targetArchive, SessionArchive.Metadata metadata) {
		Session session = getSession();
		if (session == null) {
			throw new ClaudeSDKException("No session to archive yet for working directory " + getWorkingDirectory());
		}
		return archiveSession(session.sessionId(), targetArchive, metadata);
	}

}
