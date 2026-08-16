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

package org.springaicommunity.claude.agent.sdk.config;

/**
 * Permission modes for Claude Code tool usage. Corresponds to PermissionMode in the
 * official Python SDK ({@code "default" | "acceptEdits" | "plan" | "auto" | "dontAsk" |
 * "bypassPermissions"}).
 *
 * <p>
 * The current CLI advertises the {@code --permission-mode} choices {@code acceptEdits},
 * {@code auto}, {@code bypassPermissions}, {@code manual}, {@code dontAsk} and
 * {@code plan}; {@code default} is still accepted as a hidden legacy alias of
 * {@code manual}. {@link #DANGEROUSLY_SKIP_PERMISSIONS} is the one value that does not
 * map to {@code --permission-mode} — it is passed as the separate
 * {@code --dangerously-skip-permissions} flag.
 * </p>
 */
public enum PermissionMode {

	/**
	 * Classic prompting behavior - ask for permission on tool use that is not already
	 * allowed. Legacy alias of {@link #MANUAL}; newer CLIs list this mode as
	 * {@code manual} but continue to accept {@code default}.
	 */
	DEFAULT("default"),

	/**
	 * Classic prompting behavior under its current CLI name - ask for permission on tool
	 * use that is not already allowed. Same behavior as {@link #DEFAULT}.
	 */
	MANUAL("manual"),

	/**
	 * Automatically accept edit permissions without prompting.
	 */
	ACCEPT_EDITS("acceptEdits"),

	/**
	 * Auto mode: the CLI's permission classifier decides per action - safe actions run
	 * without prompting while risky ones are still surfaced (or denied by its hard-deny
	 * rules). The classifier's environment/allow/soft_deny/hard_deny rules are
	 * user-configurable via the {@code autoMode} settings section (inspect them with
	 * {@code claude auto-mode config}).
	 */
	AUTO("auto"),

	/**
	 * Plan mode: read-only analysis in which Claude proposes a plan; execution requires
	 * the plan to be approved first.
	 */
	PLAN("plan"),

	/**
	 * Never prompt: tool use that would require an interactive permission prompt is
	 * denied automatically instead. Only actions already allowed (settings, allow rules,
	 * hooks or permission callbacks) run.
	 */
	DONT_ASK("dontAsk"),

	/**
	 * Bypass all permission checks (use with caution).
	 */
	BYPASS_PERMISSIONS("bypassPermissions"),

	/**
	 * Dangerously skip all permission checks. Recommended only for sandboxes with no
	 * internet access. Maps to the separate {@code --dangerously-skip-permissions} flag
	 * rather than a {@code --permission-mode} value.
	 */
	DANGEROUSLY_SKIP_PERMISSIONS("dangerously-skip-permissions");

	private final String value;

	PermissionMode(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	/**
	 * Creates PermissionMode from string value.
	 */
	public static PermissionMode fromValue(String value) {
		for (PermissionMode mode : values()) {
			if (mode.value.equals(value)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unknown permission mode: " + value);
	}

	@Override
	public String toString() {
		return value;
	}

}
