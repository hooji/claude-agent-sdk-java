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

package org.springaicommunity.claude.agent.sdk.usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.claude.agent.sdk.ClaudeClient;
import org.springaicommunity.claude.agent.sdk.ClaudeSyncClient;
import org.springaicommunity.claude.agent.sdk.config.PermissionMode;
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException;
import org.springaicommunity.claude.agent.sdk.parsing.ParsedMessage;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;
import org.springaicommunity.claude.agent.sdk.types.RateLimitSnapshot;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;

/**
 * Reads the account's current rate limit status — the 5-hour and 7-day usage windows of a
 * claude.ai subscription (Pro/Max) — through the CLI's supported stream-json protocol,
 * standalone: no pre-existing session or client is required.
 *
 * <p>
 * {@link #fetch()} spins up a minimal disposable probe session (Haiku by default, all
 * built-in tools disabled, a one-line system prompt, one turn, hooks and MCP servers off,
 * in a throwaway temp directory), captures the {@code rate_limit_event} the CLI emits off
 * the first API response's {@code anthropic-ratelimit-unified-*} headers, and tears the
 * session down. Measured cost of a probe is a fraction of a cent (~500 input tokens on
 * Haiku) and a few seconds of wall time; the probe itself consumes a correspondingly tiny
 * amount of the very quota it measures.
 * </p>
 *
 * <pre>{@code
 * RateLimitSnapshot snapshot = ClaudeAccountRateLimits.fetch();
 * if (snapshot != null && snapshot.fiveHour() != null) {
 *     RateLimitWindow window = snapshot.fiveHour();
 *     System.out.printf("5h window: %.0f%% used, resets %s%n", window.utilizationPercent(),
 *             window.resetsAtInstant());
 * }
 * }</pre>
 *
 * <p>
 * An application that already holds a connected {@link ClaudeSyncClient} /
 * {@link org.springaicommunity.claude.agent.sdk.ClaudeAsyncClient} does not need a probe
 * — the same data is captured on every session and exposed via
 * {@code client.latestRateLimit()} at no extra cost.
 * </p>
 *
 * <p>
 * Requirements and limits: the CLI must be new enough to emit {@code rate_limit_event}
 * with per-window data and accept {@code --tools} (2.1.x, early 2026 onward), and the
 * account must authenticate through a claude.ai subscription — API-key billing has no
 * unified rate limit windows, so {@link #fetch()} returns null for it.
 * </p>
 */
public final class ClaudeAccountRateLimits {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeAccountRateLimits.class);

	private static final String PROBE_SYSTEM_PROMPT = "You are a connectivity probe. Reply with exactly: ok";

	private static final String PROBE_PROMPT = "ok";

	private static final int STDERR_TAIL_LINES = 10;

	private ClaudeAccountRateLimits() {
	}

	/**
	 * Fetches the account's current rate limit status with default settings (Haiku probe,
	 * 2-minute timeout, CLI-managed authentication).
	 * @return the current rate limit snapshot, or null when the CLI reported none
	 * (API-key billing, or a CLI too old to emit {@code rate_limit_event})
	 * @throws ClaudeSDKException if the probe session itself fails (CLI missing, not
	 * authenticated, timeout)
	 */
	public static RateLimitSnapshot fetch() throws ClaudeSDKException {
		return fetch(FetchOptions.defaults());
	}

	/**
	 * Fetches the account's current rate limit status.
	 * @param fetchOptions probe configuration
	 * @return the current rate limit snapshot, or null when the CLI reported none
	 * (API-key billing, or a CLI too old to emit {@code rate_limit_event})
	 * @throws ClaudeSDKException if the probe session itself fails (CLI missing, not
	 * authenticated, timeout)
	 */
	public static RateLimitSnapshot fetch(FetchOptions fetchOptions) throws ClaudeSDKException {
		boolean ownWorkDir = fetchOptions.workingDirectory() == null;
		Path workDir;
		if (ownWorkDir) {
			try {
				workDir = Files.createTempDirectory("claude-rate-limit-probe");
			}
			catch (IOException e) {
				throw new ClaudeSDKException("Failed to create temp directory for rate limit probe", e);
			}
		}
		else {
			workDir = Path.of(fetchOptions.workingDirectory());
		}

		try {
			// Bounded tail of CLI stderr, for diagnostics when the probe dies early
			Deque<String> stderrTail = new ConcurrentLinkedDeque<>();
			CLIOptions.Builder cliOptions = CLIOptions.builder()
				.model(fetchOptions.model())
				.maxTurns(1)
				// --tools "" disables all built-in tools: nothing to declare, nothing to
				// permission-check, and the probe cannot touch the machine
				.tools(List.of())
				// No tools also means no permission prompts, so the SDK's
				// bypassPermissions default would only hurt: the CLI refuses it when
				// running as root (common in containers)
				.permissionMode(PermissionMode.DEFAULT)
				// Replacing the default system prompt is the main cost saver
				.systemPrompt(PROBE_SYSTEM_PROMPT)
				.settings("{\"disableAllHooks\": true}")
				.extraArg("strict-mcp-config", null)
				.timeout(fetchOptions.timeout())
				.stderrHandler(line -> {
					stderrTail.addLast(line);
					while (stderrTail.size() > STDERR_TAIL_LINES) {
						stderrTail.pollFirst();
					}
				});
			if (fetchOptions.oauthToken() != null) {
				cliOptions.oauthToken(fetchOptions.oauthToken());
			}

			try (ClaudeSyncClient client = ClaudeClient.sync(cliOptions.build())
				.workingDirectory(workDir.toString())
				.timeout(fetchOptions.timeout())
				.build()) {

				client.connect(PROBE_PROMPT);

				boolean turnCompleted = false;
				Iterator<ParsedMessage> response = client.receiveResponse();
				try {
					while (response.hasNext()) {
						ParsedMessage parsed = response.next();
						if (parsed.asMessage() instanceof ResultMessage) {
							turnCompleted = true;
						}
					}
				}
				catch (RuntimeException e) {
					// A failed turn can still have carried the event (e.g. the API
					// answered 429 with rate limit headers) — that IS the status
					if (client.latestRateLimit() == null) {
						throw e;
					}
					logger.debug("Probe turn failed after rate_limit_event was received; returning it", e);
				}

				RateLimitSnapshot snapshot = client.latestRateLimit();
				if (snapshot == null) {
					if (!turnCompleted) {
						// The CLI exited without ever answering — a startup failure,
						// not an account without rate limits
						throw new ClaudeSDKException("Rate limit probe session ended without a result"
								+ (stderrTail.isEmpty() ? "" : "; CLI stderr: " + String.join(" | ", stderrTail)));
					}
					logger.debug("Probe session completed without a rate_limit_event "
							+ "(API-key billing, or CLI too old to emit it)");
				}
				return snapshot;
			}
		}
		finally {
			if (ownWorkDir) {
				try {
					Files.deleteIfExists(workDir);
				}
				catch (IOException e) {
					logger.debug("Failed to delete probe temp directory {}", workDir, e);
				}
			}
		}
	}

	/**
	 * Configuration for a rate limit probe. {@link #defaults()} uses Haiku (the cheapest
	 * suitable model — any model's API response carries the same account-wide rate limit
	 * headers), a 2-minute timeout, a throwaway temp working directory, and whatever
	 * authentication the CLI is already configured with.
	 *
	 * @param model the model alias or id the probe's single tiny turn runs on
	 * @param timeout how long to wait for the probe session overall
	 * @param oauthToken optional long-lived {@code claude setup-token} OAuth token for
	 * headless auth, injected as {@code CLAUDE_CODE_OAUTH_TOKEN}; null to use the CLI's
	 * own login
	 * @param workingDirectory directory path to run the probe in; null to use (and clean
	 * up) a fresh temp directory, which keeps project context like CLAUDE.md out of the
	 * probe's token bill
	 */
	public record FetchOptions(String model, Duration timeout, String oauthToken, String workingDirectory) {

		/** Model used when none is configured. */
		public static final String DEFAULT_MODEL = "haiku";

		/** Timeout used when none is configured, matching the SDK's CLI default. */
		public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

		public FetchOptions {
			if (model == null || model.isBlank()) {
				model = DEFAULT_MODEL;
			}
			if (timeout == null) {
				timeout = DEFAULT_TIMEOUT;
			}
		}

		/**
		 * The default probe configuration.
		 */
		public static FetchOptions defaults() {
			return builder().build();
		}

		/**
		 * Creates a builder for custom probe configuration.
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link FetchOptions}.
		 */
		public static final class Builder {

			private String model = DEFAULT_MODEL;

			private Duration timeout = DEFAULT_TIMEOUT;

			private String oauthToken;

			private String workingDirectory;

			private Builder() {
			}

			/**
			 * Sets the probe model (default {@value #DEFAULT_MODEL}).
			 */
			public Builder model(String model) {
				this.model = model;
				return this;
			}

			/**
			 * Sets the overall probe timeout (default 2 minutes).
			 */
			public Builder timeout(Duration timeout) {
				this.timeout = timeout;
				return this;
			}

			/**
			 * Sets a long-lived OAuth token for headless authentication.
			 */
			public Builder oauthToken(String oauthToken) {
				this.oauthToken = oauthToken;
				return this;
			}

			/**
			 * Sets a fixed working directory path instead of a throwaway temp directory.
			 */
			public Builder workingDirectory(String workingDirectory) {
				this.workingDirectory = workingDirectory;
				return this;
			}

			/**
			 * Builds the options.
			 */
			public FetchOptions build() {
				return new FetchOptions(model, timeout, oauthToken, workingDirectory);
			}

		}

	}

}
