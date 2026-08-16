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

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link ClaudeCliInstaller} — command construction and configuration
 * only; nothing here touches the network or runs an installer.
 */
@DisplayName("ClaudeCliInstaller")
class ClaudeCliInstallerTest {

	@AfterEach
	void clearInstallBaseUrlOverride() {
		System.clearProperty("claude.cli.installBaseUrl");
	}

	@Test
	@DisplayName("builds the platform installer command with the target as a separate argument")
	void installerCommandShape() {
		Path script = Path.of("/tmp/claude-install-test.sh");
		List<String> command = ClaudeCliInstaller.installerCommand(script, "latest");
		if (ClaudeCliInstaller.isWindows()) {
			assertThat(command).containsExactly("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
					script.toString(), "latest");
		}
		else {
			assertThat(command).containsExactly("bash", script.toString(), "latest");
		}
	}

	@Test
	@DisplayName("uses the official claude.ai installer script by default")
	void defaultScriptUrl() {
		String url = ClaudeCliInstaller.installerScriptUrl();
		assertThat(url).startsWith("https://claude.ai/install.");
		assertThat(url).endsWith(ClaudeCliInstaller.isWindows() ? ".ps1" : ".sh");
	}

	@Test
	@DisplayName("honours the claude.cli.installBaseUrl override, trimming a trailing slash")
	void scriptUrlOverride() {
		System.setProperty("claude.cli.installBaseUrl", "https://mirror.example.com/claude/");
		assertThat(ClaudeCliInstaller.installerScriptUrl()).startsWith("https://mirror.example.com/claude/install.");
	}

	@Test
	@DisplayName("rejects a blank install target")
	void rejectsBlankTarget() {
		assertThatIllegalArgumentException().isThrownBy(() -> ClaudeCliInstaller.install("  "));
		assertThatIllegalArgumentException().isThrownBy(() -> ClaudeCliInstaller.install((String) null));
	}

}
