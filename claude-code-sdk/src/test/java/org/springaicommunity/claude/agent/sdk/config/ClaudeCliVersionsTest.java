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

package org.springaicommunity.claude.agent.sdk.config;

import com.sun.net.httpserver.HttpServer;
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliVersions.UpdateChannel;
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliVersions.UpdateResult;
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliVersions.VersionCheck;
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClaudeCliVersions}. No Claude CLI or external network access is
 * required: HTTP behavior is exercised against a local {@link HttpServer} through the
 * {@code claude.cli.registryUrl} override.
 */
class ClaudeCliVersionsTest {

	private static final String DIST_TAGS_JSON = "{\"stable\":\"2.1.197\",\"next\":\"2.1.206\",\"latest\":\"2.1.205\"}";

	@Nested
	class VersionOutputParsing {

		@Test
		void parsesVersionWithSuffix() {
			assertThat(ClaudeCliVersions.parseVersionOutput("2.1.205 (Claude Code)")).isEqualTo("2.1.205");
		}

		@Test
		void parsesBareVersion() {
			assertThat(ClaudeCliVersions.parseVersionOutput("1.0.17")).isEqualTo("1.0.17");
		}

		@Test
		void trimsWhitespaceAndNewlines() {
			assertThat(ClaudeCliVersions.parseVersionOutput("  2.1.205 (Claude Code)\n")).isEqualTo("2.1.205");
		}

		@Test
		void stripsLeadingV() {
			assertThat(ClaudeCliVersions.parseVersionOutput("v2.1.205")).isEqualTo("2.1.205");
		}

		@Test
		void rejectsEmptyOutput() {
			assertThatThrownBy(() -> ClaudeCliVersions.parseVersionOutput("  \n"))
				.isInstanceOf(ClaudeSDKException.class)
				.hasMessageContaining("Could not parse");
		}

		@Test
		void rejectsNonVersionOutput() {
			assertThatThrownBy(() -> ClaudeCliVersions.parseVersionOutput("command not found: claude"))
				.isInstanceOf(ClaudeSDKException.class)
				.hasMessageContaining("Could not parse");
		}

	}

	@Nested
	class DistTagsParsing {

		@Test
		void extractsEachChannel() {
			assertThat(ClaudeCliVersions.parseDistTags(DIST_TAGS_JSON, UpdateChannel.STABLE)).isEqualTo("2.1.197");
			assertThat(ClaudeCliVersions.parseDistTags(DIST_TAGS_JSON, UpdateChannel.LATEST)).isEqualTo("2.1.205");
			assertThat(ClaudeCliVersions.parseDistTags(DIST_TAGS_JSON, UpdateChannel.NEXT)).isEqualTo("2.1.206");
		}

		@Test
		void rejectsMissingChannel() {
			assertThatThrownBy(() -> ClaudeCliVersions.parseDistTags("{\"latest\":\"2.1.205\"}", UpdateChannel.STABLE))
				.isInstanceOf(ClaudeSDKException.class)
				.hasMessageContaining("stable");
		}

		@Test
		void rejectsMalformedJson() {
			assertThatThrownBy(() -> ClaudeCliVersions.parseDistTags("not json", UpdateChannel.LATEST))
				.isInstanceOf(ClaudeSDKException.class)
				.hasMessageContaining("Malformed");
		}

	}

	@Nested
	class VersionComparison {

		@Test
		void comparesEqualVersions() {
			assertThat(ClaudeCliVersions.compareVersions("2.1.205", "2.1.205")).isZero();
		}

		@Test
		void comparesPatchMinorAndMajor() {
			assertThat(ClaudeCliVersions.compareVersions("2.1.206", "2.1.205")).isPositive();
			assertThat(ClaudeCliVersions.compareVersions("2.2.0", "2.1.205")).isPositive();
			assertThat(ClaudeCliVersions.compareVersions("3.0.0", "2.99.99")).isPositive();
			assertThat(ClaudeCliVersions.compareVersions("2.1.197", "2.1.205")).isNegative();
		}

		@Test
		void comparesNumericallyNotLexicographically() {
			assertThat(ClaudeCliVersions.compareVersions("2.1.10", "2.1.9")).isPositive();
		}

		@Test
		void treatsMissingSegmentsAsZero() {
			assertThat(ClaudeCliVersions.compareVersions("2.1", "2.1.0")).isZero();
			assertThat(ClaudeCliVersions.compareVersions("2.1.1", "2.1")).isPositive();
		}

		@Test
		void ignoresLeadingV() {
			assertThat(ClaudeCliVersions.compareVersions("v2.1.205", "2.1.205")).isZero();
		}

		@Test
		void preReleaseSortsBeforeRelease() {
			assertThat(ClaudeCliVersions.compareVersions("2.1.0-beta", "2.1.0")).isNegative();
			assertThat(ClaudeCliVersions.compareVersions("2.1.0", "2.1.0-beta")).isPositive();
			assertThat(ClaudeCliVersions.compareVersions("2.1.0-alpha", "2.1.0-beta")).isNegative();
		}

		@Test
		void ignoresBuildMetadata() {
			assertThat(ClaudeCliVersions.compareVersions("2.1.0+build5", "2.1.0")).isZero();
		}

		@Test
		void rejectsNullOrBlank() {
			assertThatThrownBy(() -> ClaudeCliVersions.compareVersions(null, "1.0.0"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ClaudeCliVersions.compareVersions("1.0.0", " "))
				.isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	class Records {

		@Test
		void versionCheckDetectsAvailableUpdate() {
			VersionCheck check = new VersionCheck("2.1.197", "2.1.205", UpdateChannel.LATEST);
			assertThat(check.isUpdateAvailable()).isTrue();
		}

		@Test
		void versionCheckDetectsUpToDate() {
			assertThat(new VersionCheck("2.1.205", "2.1.205", UpdateChannel.LATEST).isUpdateAvailable()).isFalse();
			// Installed ahead of the channel (e.g. on 'next') is not "update available"
			assertThat(new VersionCheck("2.1.206", "2.1.205", UpdateChannel.LATEST).isUpdateAvailable()).isFalse();
		}

		@Test
		void updateResultComparesBeforeAndAfter() {
			assertThat(new UpdateResult("2.1.197", "2.1.205", 0, "ok").wasUpdated()).isTrue();
			assertThat(new UpdateResult("2.1.205", "2.1.205", 0, "already up to date").wasUpdated()).isFalse();
		}

	}

	@Nested
	class RegistryHttp {

		private HttpServer server;

		@AfterEach
		void stopServer() {
			if (server != null) {
				server.stop(0);
			}
			System.clearProperty("claude.cli.registryUrl");
		}

		private void startServer(int status, String body) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			});
			server.start();
			System.setProperty("claude.cli.registryUrl",
					"http://127.0.0.1:" + server.getAddress().getPort() + "/");
		}

		@Test
		void fetchesLatestFromRegistry() throws IOException {
			startServer(200, DIST_TAGS_JSON);
			assertThat(ClaudeCliVersions.getLatestAvailableVersion()).isEqualTo("2.1.205");
			assertThat(ClaudeCliVersions.getLatestAvailableVersion(UpdateChannel.STABLE)).isEqualTo("2.1.197");
		}

		@Test
		void reportsHttpErrorStatus() throws IOException {
			startServer(404, "{\"error\":\"Not found\"}");
			assertThatThrownBy(ClaudeCliVersions::getLatestAvailableVersion).isInstanceOf(ClaudeSDKException.class)
				.hasMessageContaining("404");
		}

		@Test
		void reportsUnreachableRegistry() {
			// Nothing is listening on this port (no server started)
			System.setProperty("claude.cli.registryUrl", "http://127.0.0.1:1/");
			assertThatThrownBy(ClaudeCliVersions::getLatestAvailableVersion).isInstanceOf(ClaudeSDKException.class)
				.hasMessageContaining("Failed to query");
		}

	}

}
