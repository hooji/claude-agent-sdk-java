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

import org.springaicommunity.claude.agent.sdk.config.ClaudeCliVersions.UpdateChannel;
import org.springaicommunity.claude.agent.sdk.config.ClaudeCliVersions.VersionCheck;
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException;
import org.springaicommunity.claude.agent.sdk.test.ClaudeCliTestBase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ClaudeCliVersions} against the real Claude CLI binary and
 * (when reachable) the real npm registry.
 *
 * <p>
 * Registry-dependent tests are skipped via assumptions when the registry is unreachable
 * (offline or restricted-network environments). {@link ClaudeCliVersions#update()} is
 * deliberately <b>not</b> exercised here: it would modify the host's Claude CLI
 * installation.
 * </p>
 */
class ClaudeCliVersionsIT extends ClaudeCliTestBase {

	private static final String SEMVER_PATTERN = "\\d+\\.\\d+\\.\\d+.*";

	@Override
	protected boolean requiresApi() {
		// These tests only exercise the CLI binary and the public npm registry
		return false;
	}

	@Test
	void getInstalledVersionReturnsSemver() {
		String version = ClaudeCliVersions.getInstalledVersion();
		assertThat(version).matches(SEMVER_PATTERN);
		// No decoration from the raw CLI output ("2.1.205 (Claude Code)")
		assertThat(version).doesNotContain(" ").doesNotContain("(");
	}

	@Test
	void getInstalledVersionWithExplicitPathMatchesDiscovered() {
		String viaDiscovery = ClaudeCliVersions.getInstalledVersion();
		String viaExplicitPath = ClaudeCliVersions.getInstalledVersion(getClaudeCliPath());
		assertThat(viaExplicitPath).isEqualTo(viaDiscovery);
	}

	@Test
	void getLatestAvailableVersionReturnsSemverForEachChannel() {
		String latest = fetchOrSkip(UpdateChannel.LATEST);
		String stable = fetchOrSkip(UpdateChannel.STABLE);
		assertThat(latest).matches(SEMVER_PATTERN);
		assertThat(stable).matches(SEMVER_PATTERN);
		// The stable channel trails (or equals) the latest channel
		assertThat(ClaudeCliVersions.compareVersions(stable, latest)).isLessThanOrEqualTo(0);
	}

	@Test
	void checkForUpdateCombinesBothSources() {
		fetchOrSkip(UpdateChannel.LATEST);
		VersionCheck check = ClaudeCliVersions.checkForUpdate();
		assertThat(check.channel()).isEqualTo(UpdateChannel.LATEST);
		assertThat(check.installedVersion()).isEqualTo(ClaudeCliVersions.getInstalledVersion());
		assertThat(check.latestVersion()).matches(SEMVER_PATTERN);
		// Consistency: update is available iff the channel is strictly ahead
		boolean latestIsAhead = ClaudeCliVersions.compareVersions(check.latestVersion(),
				check.installedVersion()) > 0;
		assertThat(check.isUpdateAvailable()).isEqualTo(latestIsAhead);
	}

	private static String fetchOrSkip(UpdateChannel channel) {
		try {
			return ClaudeCliVersions.getLatestAvailableVersion(channel);
		}
		catch (ClaudeSDKException e) {
			Assumptions.abort("npm registry unreachable from this environment: " + e.getMessage());
			return null; // unreachable
		}
	}

}
