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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.sessions.ClaudeCloudSessions.CloudSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link CloudSessionWatch} and
 * {@link ClaudeCloudSessions#getCloudSession(String, String)} against a local stub of the
 * cloud sessions API (wired in via the {@code claude.sessions.baseUrl} system property).
 * The watch under test is constructed through the package-private constructor so it can
 * poll fast; the public factories clamp to the 15-second good-citizen floor.
 */
@DisplayName("CloudSessionWatch")
class CloudSessionWatchTest {

	private static final String SESSION_ID = "cse_watchtest";

	/** One stubbed HTTP answer. */
	private record StubResponse(int status, String body) {
	}

	private HttpServer server;

	@AfterEach
	void tearDown() {
		System.clearProperty("claude.sessions.baseUrl");
		if (server != null) {
			server.stop(0);
		}
	}

	/**
	 * Starts a stub answering all {@code /v1/code/sessions...} requests via
	 * {@code responder} (1-based call index, request path → response) and registers its
	 * address as the API base URL. Returns the call counter.
	 */
	private AtomicInteger startStub(BiFunction<Integer, String, StubResponse> responder) throws IOException {
		AtomicInteger calls = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/code/sessions", exchange -> {
			StubResponse response = responder.apply(calls.incrementAndGet(), exchange.getRequestURI().getPath());
			byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(response.status(), body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		System.setProperty("claude.sessions.baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
		return calls;
	}

	private static String session(String workerStatus) {
		return "{\"id\": \"" + SESSION_ID + "\", \"worker_status\": \"" + workerStatus + "\"}";
	}

	private static void awaitInactive(CloudSessionWatch watch) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (watch.isActive() && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(watch.isActive()).isFalse();
	}

	@Test
	@DisplayName("fires once when the session transitions to idle, then closes itself")
	void firesWhenSessionGoesIdle() throws Exception {
		AtomicInteger calls = startStub((call, path) -> new StubResponse(200, session(call < 3 ? "running" : "idle")));
		CountDownLatch fired = new CountDownLatch(1);
		AtomicReference<CloudSession> observed = new AtomicReference<>();

		CloudSessionWatch watch = new CloudSessionWatch("tok", SESSION_ID, Duration.ofMillis(50), s -> {
			observed.set(s);
			fired.countDown();
		}, null);
		try {
			assertThat(fired.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(observed.get().isIdle()).isTrue();
			assertThat(watch.turnEnded()).isTrue();
			awaitInactive(watch);
			int callsAtFire = calls.get();
			Thread.sleep(300);
			assertThat(calls.get()).isEqualTo(callsAtFire); // polling stopped
		}
		finally {
			watch.close();
		}
	}

	@Test
	@DisplayName("fires immediately when the session is already blocked on the user")
	void firesImmediatelyWhenAlreadyRequiresAction() throws Exception {
		startStub((call, path) -> new StubResponse(200, session("requires_action")));
		CountDownLatch fired = new CountDownLatch(1);
		AtomicReference<CloudSession> observed = new AtomicReference<>();

		CloudSessionWatch watch = new CloudSessionWatch("tok", SESSION_ID, Duration.ofMinutes(5), s -> {
			observed.set(s);
			fired.countDown();
		}, null);
		try {
			// The first poll happens at t=0 regardless of the interval.
			assertThat(fired.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(observed.get().requiresAction()).isTrue();
		}
		finally {
			watch.close();
		}
	}

	@Test
	@DisplayName("reports and stops when the session disappears")
	void stopsWhenSessionDisappears() throws Exception {
		// By-id path → 404; the list fallback (bare collection path) serves an empty
		// page — so the session is definitively gone.
		startStub((call, path) -> path.endsWith("/" + SESSION_ID) ? new StubResponse(404, "not found")
				: new StubResponse(200, "{\"data\":[]}"));
		CountDownLatch errored = new CountDownLatch(1);
		AtomicReference<Exception> error = new AtomicReference<>();

		CloudSessionWatch watch = new CloudSessionWatch("tok", SESSION_ID, Duration.ofMillis(50), s -> {
		}, e -> {
			error.set(e);
			errored.countDown();
		});
		try {
			assertThat(errored.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(error.get()).hasMessageContaining("not found");
			assertThat(watch.turnEnded()).isFalse();
			awaitInactive(watch);
		}
		finally {
			watch.close();
		}
	}

	@Test
	@DisplayName("gives up after consecutive polling failures")
	void givesUpAfterConsecutiveFailures() throws Exception {
		startStub((call, path) -> new StubResponse(500, "boom"));
		CountDownLatch reports = new CountDownLatch(CloudSessionWatch.MAX_CONSECUTIVE_FAILURES);
		AtomicReference<Exception> lastError = new AtomicReference<>();

		CloudSessionWatch watch = new CloudSessionWatch("tok", SESSION_ID, Duration.ofMillis(30), s -> {
		}, e -> {
			lastError.set(e);
			reports.countDown();
		});
		try {
			assertThat(reports.await(15, TimeUnit.SECONDS)).isTrue();
			awaitInactive(watch);
			assertThat(lastError.get()).hasMessageContaining("giving up");
			assertThat(watch.turnEnded()).isFalse();
		}
		finally {
			watch.close();
		}
	}

	@Test
	@DisplayName("the public factory validates arguments and documents the 15s floor")
	void publicFactoryValidates() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ClaudeCloudSessions.watchForTurnEnd("tok", " ", Duration.ofSeconds(30), s -> {
			}));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ClaudeCloudSessions.watchForTurnEnd("tok", SESSION_ID, Duration.ofSeconds(30), null));
		assertThat(CloudSessionWatch.MIN_POLL_INTERVAL).isEqualTo(Duration.ofSeconds(15));
	}

	@Test
	@DisplayName("getCloudSession: direct hit on the by-id endpoint")
	void getCloudSessionDirect() throws Exception {
		startStub((call, path) -> new StubResponse(200, session("running")));
		Optional<CloudSession> s = ClaudeCloudSessions.getCloudSession("tok", SESSION_ID);
		assertThat(s).isPresent();
		assertThat(s.get().id()).isEqualTo(SESSION_ID);
	}

	@Test
	@DisplayName("getCloudSession: 404 on the by-id path falls back to scanning the paged list")
	void getCloudSessionFallsBackToList() throws Exception {
		// Call 1: by-id → 404. Call 2: list page 1 (another session, with a cursor).
		// Call 3: list page 2 carrying the wanted session.
		startStub((call, path) -> switch (call) {
			case 1 -> new StubResponse(404, "not found");
			case 2 -> new StubResponse(200, "{\"data\":[{\"id\":\"cse_other\"}],\"next_cursor\":\"c2\"}");
			default -> new StubResponse(200, "{\"data\":[" + session("idle") + "]}");
		});
		Optional<CloudSession> s = ClaudeCloudSessions.getCloudSession("tok", SESSION_ID);
		assertThat(s).isPresent();
		assertThat(s.get().isIdle()).isTrue();
	}

	@Test
	@DisplayName("getCloudSession: empty when neither the by-id endpoint nor the list knows the id")
	void getCloudSessionEmptyWhenAbsent() throws Exception {
		startStub((call, path) -> call == 1 ? new StubResponse(404, "not found")
				: new StubResponse(200, "{\"data\":[{\"id\":\"cse_other\"}]}"));
		assertThat(ClaudeCloudSessions.getCloudSession("tok", SESSION_ID)).isEmpty();
	}

}
