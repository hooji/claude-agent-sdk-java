/*
 * Copyright 2026 Spring AI Community
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

package org.springaicommunity.claude.agent.sdk.sessions;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.claude.agent.sdk.sessions.ClaudeCloudSessions.CloudSession;

/**
 * A one-shot watch on a single Claude Code cloud session that invokes a callback when the
 * session <em>finishes its turn</em> — i.e. its {@code worker_status} is observed as
 * {@code "idle"} (done, awaiting the next instruction) or {@code "requires_action"}
 * (blocked on the user: permission prompt / plan approval). Created via
 * {@link ClaudeCloudSessions#watchForTurnEnd}.
 *
 * <p>
 * The cloud sessions API has no push channel, so the watch polls
 * {@link ClaudeCloudSessions#getCloudSession(String, String)} on a single daemon thread.
 * To be a good citizen against this (undocumented) API, the public factories never poll
 * more often than every {@link #MIN_POLL_INTERVAL 15 seconds} — a shorter requested
 * interval is clamped up.
 * </p>
 *
 * <p>
 * Semantics:
 * </p>
 * <ul>
 * <li><b>One-shot:</b> the callback fires at most once, after which the watch closes
 * itself. If the session is already idle / requiring action on the first poll, it fires
 * immediately (the turn is already over).</li>
 * <li><b>Transient errors</b> (network blips, 5xx) are reported to the error handler (or
 * logged) and polling continues; after {@value #MAX_CONSECUTIVE_FAILURES} consecutive
 * failures — or if the session disappears entirely — the watch gives up and closes,
 * reporting the final error.</li>
 * <li>The callback and error handler run on the polling thread; keep them quick or hand
 * off to your own executor.</li>
 * </ul>
 *
 * <pre>{@code
 * try (CloudSessionWatch watch = ClaudeCloudSessions.watchForTurnEnd(token, sessionId,
 *         Duration.ofSeconds(15), session -> notifyMe(session))) {
 *     // ... do other work; the callback fires on a daemon thread ...
 * }
 * }</pre>
 */
public final class CloudSessionWatch implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(CloudSessionWatch.class);

	/** The good-citizen floor (and default) for the polling interval. */
	public static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(15);

	/** Consecutive polling failures after which the watch gives up. */
	static final int MAX_CONSECUTIVE_FAILURES = 8;

	private final String token;

	private final String sessionId;

	private final Consumer<CloudSession> onTurnEnd;

	private final Consumer<Exception> onError;

	private final ScheduledExecutorService executor;

	private final AtomicBoolean fired = new AtomicBoolean(false);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

	/**
	 * Package-private: created through {@link ClaudeCloudSessions#watchForTurnEnd}. The
	 * interval arrives pre-clamped from the public factories; tests use this constructor
	 * directly to poll faster against a local stub server.
	 */
	CloudSessionWatch(String token, String sessionId, Duration pollInterval, Consumer<CloudSession> onTurnEnd,
			Consumer<Exception> onError) {
		this.token = token;
		this.sessionId = sessionId;
		this.onTurnEnd = onTurnEnd;
		this.onError = onError;
		this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "claude-cloud-session-watch-" + sessionId);
			t.setDaemon(true);
			return t;
		});
		this.executor.scheduleWithFixedDelay(this::poll, 0, Math.max(1, pollInterval.toMillis()),
				TimeUnit.MILLISECONDS);
	}

	/** The watched session id. */
	public String sessionId() {
		return sessionId;
	}

	/** Whether the turn-end callback has fired. */
	public boolean turnEnded() {
		return fired.get();
	}

	/**
	 * Whether the watch is still polling (it has neither fired, given up, nor been
	 * closed).
	 */
	public boolean isActive() {
		return !closed.get();
	}

	private void poll() {
		if (closed.get()) {
			return;
		}
		try {
			Optional<CloudSession> session = ClaudeCloudSessions.getCloudSession(token, sessionId);
			consecutiveFailures.set(0);
			if (session.isEmpty()) {
				giveUp(new IOException("cloud session " + sessionId + " not found — it can no longer finish a turn"));
				return;
			}
			CloudSession s = session.get();
			if (s.isIdle() || s.requiresAction()) {
				fire(s);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (Exception e) {
			int failures = consecutiveFailures.incrementAndGet();
			if (failures >= MAX_CONSECUTIVE_FAILURES) {
				giveUp(new IOException("giving up watching cloud session " + sessionId + " after " + failures
						+ " consecutive polling failures; last: " + e.getMessage(), e));
			}
			else {
				reportError(e);
			}
		}
	}

	private void fire(CloudSession session) {
		if (fired.compareAndSet(false, true)) {
			try {
				onTurnEnd.accept(session);
			}
			catch (Exception e) {
				logger.warn("Turn-end callback for cloud session {} threw", sessionId, e);
			}
			close();
		}
	}

	private void giveUp(Exception error) {
		reportError(error);
		close();
	}

	private void reportError(Exception error) {
		if (onError != null) {
			try {
				onError.accept(error);
			}
			catch (Exception e) {
				logger.warn("Error handler for cloud session watch {} threw", sessionId, e);
			}
		}
		else {
			logger.warn("Cloud session watch {}: {}", sessionId, error.getMessage());
		}
	}

	/** Stops polling. Idempotent; called automatically after the callback fires. */
	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			executor.shutdownNow();
		}
	}

}
