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

package org.springaicommunity.claude.agent.sdk.types;

/**
 * Base interface for all message types. Corresponds to Message union type in Python SDK.
 *
 * <p>
 * Historical note: this interface used to carry Jackson polymorphic-type annotations
 * ({@code @JsonTypeInfo}/{@code @JsonSubTypes}) registering the four CLI wire types
 * (user/assistant/system/result). They were removed because they were never the SDK's
 * parsing mechanism — {@link org.springaicommunity.claude.agent.sdk.parsing.MessageParser}
 * hand-parses the CLI wire format, whose nested shape doesn't bind to these records — and
 * the registration had gone stale: it predated {@code StreamEvent} and the replay-only
 * types ({@code ForkMarker}, {@code HistoryEnd}, {@code RawTranscriptMessage}, the latter
 * with a dynamic type id that NAME-based polymorphism cannot express). Serializing those
 * as {@code Message} produced duplicate, conflicting {@code "type"} keys and output that
 * failed to deserialize. JSON (de)serialization of this hierarchy via Jackson is
 * therefore deliberately unsupported; use {@code MessageParser} for wire JSON.
 * </p>
 */
public interface Message {

	/**
	 * Returns the type of this message.
	 * @return the message type
	 */
	String getType();

}