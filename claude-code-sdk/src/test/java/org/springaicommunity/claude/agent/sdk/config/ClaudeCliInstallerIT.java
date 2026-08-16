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

package org.springaicommunity.claude.agent.sdk.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.test.ClaudeCliTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ClaudeCliInstaller} detection against the real CLI on this
 * machine. Requires the {@code claude} binary but no live model — installation itself is
 * deliberately not exercised (it would modify the machine).
 */
@DisplayName("ClaudeCliInstaller (integration)")
class ClaudeCliInstallerIT extends ClaudeCliTestBase {

	@Override
	protected boolean requiresApi() {
		return false;
	}

	@Test
	@DisplayName("detects the installed CLI")
	void detectsInstalledCli() {
		assertThat(ClaudeCliInstaller.isInstalled()).isTrue();
		assertThat(ClaudeCliInstaller.installedPath()).isPresent();
	}

	@Test
	@DisplayName("ensureInstalled() is a verified no-op when the CLI is present")
	void ensureInstalledNoOp() {
		ClaudeCliInstaller.InstallResult result = ClaudeCliInstaller.ensureInstalled();
		assertThat(result.alreadyInstalled()).isTrue();
		assertThat(result.freshlyInstalled()).isFalse();
		assertThat(result.path()).isNotBlank();
		assertThat(result.version()).matches("\\d+\\.\\d+.*");
		assertThat(result.installerOutput()).isEmpty();
	}

}
