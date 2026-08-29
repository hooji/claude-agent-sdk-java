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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.claude.agent.sdk.parsing.ParsedMessage;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression test: when the CLI process exits without ever producing a result (e.g. a
 * startup failure such as refusing {@code bypassPermissions} under root), the sync
 * client's blocking receivers must terminate instead of waiting forever. The transport
 * signals this by pushing {@link ParsedMessage.EndOfStream} through the message-handler
 * path when the CLI's stdout closes.
 */
class ClientEndOfStreamTest {

	@TempDir
	Path tempDir;

	private String stubCliThatDiesAtStartup() throws Exception {
		Path script = tempDir.resolve("dead-claude.sh");
		Files.writeString(script, """
				#!/bin/sh
				echo "simulated startup failure" >&2
				exit 1
				""");
		Files.setPosixFilePermissions(script, EnumSet.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		return script.toString();
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void receiveResponseTerminatesWhenCliDiesAtStartup() throws Exception {
		String stub = stubCliThatDiesAtStartup();
		CLIOptions options = CLIOptions.builder().timeout(Duration.ofSeconds(15)).build();

		assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
			try (DefaultClaudeSyncClient client = new DefaultClaudeSyncClient(tempDir.toString(), options,
					Duration.ofSeconds(15), stub, null)) {
				try {
					client.connect("hello");
					Iterator<ParsedMessage> response = client.receiveResponse();
					while (response.hasNext()) {
						response.next();
					}
				}
				catch (RuntimeException expected) {
					// Connect or drain may surface the dead process as an exception —
					// acceptable; the regression being tested is hanging forever
				}
			}
		});
	}

}
