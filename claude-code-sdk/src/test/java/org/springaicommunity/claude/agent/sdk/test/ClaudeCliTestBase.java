/*
 * Copyright 2024 Spring AI Community
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

package org.springaicommunity.claude.agent.sdk.test;

import org.springaicommunity.claude.agent.sdk.config.ClaudeCliDiscovery;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Base class for tests that require Claude CLI to be available.
 *
 * <p>
 * This class automatically discovers the Claude CLI executable and makes it available to
 * subclasses. If Claude CLI cannot be found, all tests will fail with a clear message
 * indicating the issue.
 * </p>
 *
 * <p>
 * Subclasses can access the discovered CLI path via {@link #getClaudeCliPath()}.
 * </p>
 *
 * <p>
 * <strong>Usage:</strong>
 * </p>
 * <pre>
 * class MyClaudeTest extends ClaudeCliTestBase {
 *
 *     {@literal @}Test
 *     void testSomething() {
 *         // Claude CLI is guaranteed to be available here
 *         String claudePath = getClaudeCliPath();
 *         // ... test implementation
 *     }
 * }
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ClaudeCliTestBase {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeCliTestBase.class);

	private static String claudeCliPath;

	/**
	 * Discovers Claude CLI before any tests run. If discovery fails, all tests in
	 * subclasses will fail with a clear error message.
	 */
	@BeforeAll
	static void discoverClaudeCli() {
		try {
			claudeCliPath = org.springaicommunity.claude.agent.sdk.config.ClaudeCliDiscovery.discoverClaudePath();
			logger.info("Claude CLI tests will use executable at: {}", claudeCliPath);
		}
		catch (org.springaicommunity.claude.agent.sdk.config.ClaudeCliDiscovery.ClaudeCliNotFoundException e) {
			String errorMsg = "Claude CLI Integration Tests Failed: " + e.getMessage();
			logger.error(errorMsg);

			// Throw a runtime exception that will cause all tests to fail with a clear
			// message
			throw new ClaudeCliNotAvailableException(errorMsg);
		}
	}

	/**
	 * Skips API-dependent tests when no Claude credentials are available (e.g. CI
	 * runners without an {@code ANTHROPIC_API_KEY} secret). Runs after CLI discovery;
	 * subclasses that exercise only the CLI binary (no model) override
	 * {@link #requiresApi()} to keep running without credentials.
	 */
	@BeforeAll
	void checkApiRequirement() {
		if (requiresApi()) {
			Assumptions.assumeTrue(apiCredentialsAvailable(),
					"Skipping: this test talks to a live Claude model and no credentials were detected "
							+ "(set ANTHROPIC_API_KEY / CLAUDE_CODE_OAUTH_TOKEN, log in with the CLI, "
							+ "or force with CLAUDE_SDK_LIVE_TESTS=true)");
		}
	}

	/**
	 * Whether this test class needs a live Claude model behind the CLI. Defaults to
	 * {@code true}; override to {@code false} for tests that only exercise the CLI
	 * binary (e.g. flag parity via {@code claude --help}).
	 */
	protected boolean requiresApi() {
		return true;
	}

	/**
	 * Best-effort detection of usable Claude credentials: an API key or OAuth token in
	 * the environment, a CLI login on this machine, or the explicit
	 * {@code CLAUDE_SDK_LIVE_TESTS=true} opt-in (for environments with host-managed
	 * auth that is invisible to this process).
	 */
	protected static boolean apiCredentialsAvailable() {
		if ("true".equalsIgnoreCase(System.getenv("CLAUDE_SDK_LIVE_TESTS"))) {
			return true;
		}
		if (isSet(System.getenv("ANTHROPIC_API_KEY")) || isSet(System.getenv("CLAUDE_CODE_OAUTH_TOKEN"))) {
			return true;
		}
		Path credentials = Paths.get(System.getProperty("user.home"), ".claude", ".credentials.json");
		return Files.isRegularFile(credentials);
	}

	private static boolean isSet(String value) {
		return value != null && !value.isBlank();
	}

	/**
	 * Gets the discovered Claude CLI executable path.
	 * @return the path to Claude CLI executable
	 * @throws IllegalStateException if Claude CLI discovery hasn't been performed yet
	 */
	protected static String getClaudeCliPath() {
		if (claudeCliPath == null) {
			throw new IllegalStateException("Claude CLI path not discovered. Ensure @BeforeAll method has run.");
		}
		return claudeCliPath;
	}

	/**
	 * Checks if Claude CLI is available for testing.
	 * @return true if Claude CLI is available, false otherwise
	 */
	protected static boolean isClaudeCliAvailable() {
		return claudeCliPath != null;
	}

	/**
	 * Gets the working directory for test execution. Can be overridden by subclasses if
	 * needed.
	 * @return the working directory path
	 */
	protected String workingDirectory() {
		return ".";
	}

	/**
	 * Exception thrown when Claude CLI is not available for testing. This is a runtime
	 * exception that will cause test execution to fail.
	 */
	public static class ClaudeCliNotAvailableException extends RuntimeException {

		public ClaudeCliNotAvailableException(String message) {
			super(message);
		}

	}

}