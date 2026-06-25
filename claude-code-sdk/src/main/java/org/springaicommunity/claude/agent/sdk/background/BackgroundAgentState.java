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

package org.springaicommunity.claude.agent.sdk.background;

import java.util.Locale;

/**
 * Lifecycle state of a background agent, as reported by the {@code state} field of
 * {@code claude agents --json}. {@link #WORKING} and {@link #BLOCKED} are live;
 * {@link #DONE}, {@link #FAILED}, and {@link #STOPPED} are {@linkplain #isTerminal()
 * terminal}.
 */
public enum BackgroundAgentState {

	/** Actively running tools or generating. */
	WORKING,

	/** Waiting on the user (e.g. a permission prompt or required input). */
	BLOCKED,

	/** The task finished successfully. */
	DONE,

	/** The task ended with an error. */
	FAILED,

	/** Stopped via {@code claude stop} (or otherwise terminated by request). */
	STOPPED,

	/** State string not recognized (forward-compatible default). */
	UNKNOWN;

	/** @return whether this is a terminal state (the agent will not progress further). */
	public boolean isTerminal() {
		return this == DONE || this == FAILED || this == STOPPED;
	}

	/** Maps a {@code state} string from {@code claude agents --json} to an enum value. */
	static BackgroundAgentState fromWire(String wire) {
		if (wire == null) {
			return UNKNOWN;
		}
		return switch (wire.toLowerCase(Locale.ROOT)) {
			case "working", "running" -> WORKING;
			case "blocked", "waiting" -> BLOCKED;
			case "done", "completed", "complete" -> DONE;
			case "failed", "error", "errored" -> FAILED;
			case "stopped", "killed", "cancelled", "canceled" -> STOPPED;
			default -> UNKNOWN;
		};
	}

}
