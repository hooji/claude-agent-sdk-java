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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.config.PermissionMode;
import org.springaicommunity.claude.agent.sdk.hooks.HookRegistry;
import org.springaicommunity.claude.agent.sdk.parsing.ParsedMessage;
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions;
import org.springaicommunity.claude.agent.sdk.types.Message;
import org.springaicommunity.claude.agent.sdk.types.control.HookEvent;
import org.springaicommunity.claude.agent.sdk.types.control.HookOutput;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for ClaudeClient.async() factory and ClaudeAsyncClient.
 */
class ClaudeAsyncClientTest {

	private String workingDirectory;

	@BeforeEach
	void setUp() {
		workingDirectory = System.getProperty("user.dir");
	}

	@Nested
	@DisplayName("ClaudeClient.async() Factory Tests")
	class FactoryTests {

		@Test
		@DisplayName("should create AsyncSpec from factory")
		void shouldCreateAsyncSpec() {
			ClaudeClient.AsyncSpec spec = ClaudeClient.async();
			assertThat(spec).isNotNull();
		}

		@Test
		@DisplayName("should build client with required parameters")
		void shouldBuildWithRequiredParams() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			assertThat(client).isNotNull();
			assertThat(client.isConnected()).isFalse();

			client.close();
		}

		@Test
		@DisplayName("should build client with all parameters")
		void shouldBuildWithAllParams() {
			HookRegistry registry = new HookRegistry();

			ClaudeAsyncClient client = ClaudeClient.async()
				.workingDirectory(workingDirectory)
				.timeout(Duration.ofMinutes(5))
				.claudePath("/usr/bin/claude")
				.hookRegistry(registry)
				.model("claude-sonnet-4-20250514")
				.systemPrompt("You are a helpful assistant")
				.maxTokens(1000)
				.maxThinkingTokens(500)
				.allowedTools(List.of("Read", "Write"))
				.disallowedTools(List.of("Bash"))
				.permissionMode(PermissionMode.ACCEPT_EDITS)
				.maxTurns(10)
				.maxBudgetUsd(1.0)
				.build();

			assertThat(client).isNotNull();
			assertThat(client.getServerInfo()).isEmpty();

			client.close();
		}

		@Test
		@DisplayName("should throw when working directory is null")
		void shouldThrowWhenWorkingDirNull() {
			assertThatThrownBy(() -> ClaudeClient.async().build()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("workingDirectory");
		}

	}

	@Nested
	@DisplayName("ClaudeClient.async(CLIOptions) Factory Tests")
	class FactoryWithOptionsTests {

		@Test
		@DisplayName("should create AsyncSpecWithOptions from factory")
		void shouldCreateAsyncSpecWithOptions() {
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			ClaudeClient.AsyncSpecWithOptions spec = ClaudeClient.async(options);
			assertThat(spec).isNotNull();
		}

		@Test
		@DisplayName("should build client with CLIOptions and required parameters")
		void shouldBuildWithCLIOptionsAndRequiredParams() {
			CLIOptions options = CLIOptions.builder()
				.model("claude-haiku-4-5-20251001")
				.systemPrompt("Be concise")
				.build();

			ClaudeAsyncClient client = ClaudeClient.async(options).workingDirectory(workingDirectory).build();

			assertThat(client).isNotNull();
			assertThat(client.isConnected()).isFalse();

			client.close();
		}

		@Test
		@DisplayName("should build client with CLIOptions and all session parameters")
		void shouldBuildWithCLIOptionsAndAllSessionParams() {
			CLIOptions options = CLIOptions.builder()
				.model("claude-haiku-4-5-20251001")
				.systemPrompt("Be concise")
				.maxTokens(1000)
				.build();
			HookRegistry registry = new HookRegistry();

			ClaudeAsyncClient client = ClaudeClient.async(options)
				.workingDirectory(workingDirectory)
				.timeout(Duration.ofMinutes(5))
				.claudePath("/usr/bin/claude")
				.hookRegistry(registry)
				.build();

			assertThat(client).isNotNull();

			client.close();
		}

		@Test
		@DisplayName("should throw when working directory is null with CLIOptions")
		void shouldThrowWhenWorkingDirNullWithCLIOptions() {
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			assertThatThrownBy(() -> ClaudeClient.async(options).build()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("workingDirectory");
		}

		@Test
		@DisplayName("AsyncSpecWithOptions should not expose CLI option setters")
		void shouldNotExposeCLIOptionSetters() {
			// Verify that AsyncSpecWithOptions only has session-level methods
			// by checking it doesn't have model(), systemPrompt(), etc.
			// This is a compile-time guarantee, but we can verify the available methods
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			ClaudeClient.AsyncSpecWithOptions spec = ClaudeClient.async(options);

			// These methods should exist (session-level)
			assertThat(spec.workingDirectory(workingDirectory)).isSameAs(spec);
			assertThat(spec.timeout(Duration.ofMinutes(5))).isSameAs(spec);
			assertThat(spec.claudePath("/usr/bin/claude")).isSameAs(spec);
			assertThat(spec.hookRegistry(new HookRegistry())).isSameAs(spec);

			// Note: model(), systemPrompt(), etc. don't exist on AsyncSpecWithOptions
			// This is enforced at compile time
		}

	}

	@Nested
	@DisplayName("Client State Tests")
	class ClientStateTests {

		@Test
		@DisplayName("should not be connected after creation")
		void shouldNotBeConnectedAfterCreation() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			assertThat(client.isConnected()).isFalse();

			client.close();
		}

		@Test
		@DisplayName("should error when querying without connection")
		void shouldErrorWhenQueryingWithoutConnection() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			// TurnSpec is lazy - error happens when we subscribe to a terminal method
			StepVerifier.create(client.query("test").text())
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			client.close();
		}

		@Test
		@DisplayName("should error when interrupting without connection")
		void shouldErrorWhenInterruptingWithoutConnection() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			StepVerifier.create(client.interrupt())
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			client.close();
		}

		@Test
		@DisplayName("should error when setting permission mode without connection")
		void shouldErrorWhenSettingPermissionModeWithoutConnection() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			StepVerifier.create(client.setPermissionMode("acceptEdits"))
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			client.close();
		}

		@Test
		@DisplayName("should error when setting model without connection")
		void shouldErrorWhenSettingModelWithoutConnection() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			StepVerifier.create(client.setModel("claude-opus-4-20250514"))
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			client.close();
		}

	}

	@Nested
	@DisplayName("Hook Registration Tests")
	class HookRegistrationTests {

		@Test
		@DisplayName("should register hook via DefaultClaudeAsyncClient")
		void shouldRegisterHook() {
			DefaultClaudeAsyncClient client = (DefaultClaudeAsyncClient) ClaudeClient.async()
				.workingDirectory(workingDirectory)
				.build();

			client.registerHook(HookEvent.PRE_TOOL_USE, "Bash", input -> HookOutput.allow());

			// No exception thrown = success
			client.close();
		}

		@Test
		@DisplayName("should support fluent hook registration")
		void shouldSupportFluentRegistration() {
			DefaultClaudeAsyncClient client = (DefaultClaudeAsyncClient) ClaudeClient.async()
				.workingDirectory(workingDirectory)
				.build();

			DefaultClaudeAsyncClient result = client
				.registerHook(HookEvent.PRE_TOOL_USE, "Bash", input -> HookOutput.allow())
				.registerHook(HookEvent.POST_TOOL_USE, "Edit", input -> HookOutput.allow());

			assertThat(result).isSameAs(client);

			client.close();
		}

	}

	@Nested
	@DisplayName("Close Tests")
	class CloseTests {

		@Test
		@DisplayName("should be idempotent on close")
		void shouldBeIdempotentOnClose() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			// Multiple closes should not error
			client.close();
			client.close();
			client.close();
		}

		@Test
		@DisplayName("disconnect should be alias for close")
		void disconnectShouldAliasClose() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			client.disconnect();
			assertThat(client.isConnected()).isFalse();
		}

	}

	@Nested
	@DisplayName("Flux.defer() Regression Tests")
	class FluxDeferRegressionTests {

		@Test
		@DisplayName("receiveResponse should defer connected check until subscription")
		void receiveResponseShouldDeferConnectedCheck() {
			// This test verifies the Flux.defer() fix for the race condition where
			// receiveResponse() is called when building a reactive chain BEFORE
			// connect() completes.
			//
			// Without Flux.defer(), this pattern would fail:
			// client.connect("...").thenMany(client.receiveResponse())
			//
			// Because receiveResponse() is evaluated when the chain is built,
			// not when thenMany subscribes after connect completes.

			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			// Build a Flux from receiveResponse() - this should NOT throw/error yet
			// even though not connected, because of Flux.defer()
			var responseFlux = client.receiveResponse();

			// The Flux should be created successfully (deferred check)
			assertThat(responseFlux).isNotNull();

			// But subscribing should error because we're not connected
			StepVerifier.create(responseFlux)
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			client.close();
		}

		@Test
		@DisplayName("receiveMessages should defer connected check until subscription")
		void receiveMessagesShouldDeferConnectedCheck() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			// Build a Flux from receiveMessages() - should NOT error yet
			var messagesFlux = client.receiveMessages();

			assertThat(messagesFlux).isNotNull();

			// Subscribing should error
			StepVerifier.create(messagesFlux)
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			client.close();
		}

	}

	@Nested
	@DisplayName("Server Info Tests")
	class ServerInfoTests {

		@Test
		@DisplayName("should return empty server info when not connected")
		void shouldReturnEmptyServerInfoWhenNotConnected() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			assertThat(client.getServerInfo()).isEmpty();

			client.close();
		}

	}

	@Nested
	@DisplayName("Convenience Method Tests")
	class ConvenienceMethodTests {

		@Test
		@DisplayName("connectAndReceive should defer connected check")
		void connectAndReceiveShouldDeferConnectedCheck() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			// Building the Flux should not error (deferred)
			var flux = client.connectAndReceive("test");
			assertThat(flux).isNotNull();

			client.close();
		}

		@Test
		@DisplayName("queryAndReceive should defer connected check")
		void queryAndReceiveShouldDeferConnectedCheck() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			var flux = client.queryAndReceive("test");
			assertThat(flux).isNotNull();

			client.close();
		}

		@Test
		@DisplayName("connectText should defer connected check")
		void connectTextShouldDeferConnectedCheck() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			var flux = client.connectText("test");
			assertThat(flux).isNotNull();

			client.close();
		}

		@Test
		@DisplayName("queryText should defer connected check")
		void queryTextShouldDeferConnectedCheck() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			var flux = client.queryText("test");
			assertThat(flux).isNotNull();

			client.close();
		}

	}

	@Nested
	@DisplayName("Tool Permission Callback Tests")
	class ToolPermissionCallbackTests {

		@Test
		@DisplayName("should get null callback by default")
		void shouldGetNullCallbackByDefault() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			assertThat(client.getToolPermissionCallback()).isNull();

			client.close();
		}

		@Test
		@DisplayName("should set and get tool permission callback")
		void shouldSetAndGetCallback() {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			client.setToolPermissionCallback((toolName, input, context) -> {
				return org.springaicommunity.claude.agent.sdk.permission.PermissionResult.allow();
			});

			assertThat(client.getToolPermissionCallback()).isNotNull();

			client.close();
		}

	}

	@Nested
	@DisplayName("close() Cleanup Isolation Tests")
	class CloseCleanupTests {

		/**
		 * A sink completion can fail for reasons outside this client's control - most
		 * sharply when the client is driven over a remoting layer and its subscribers
		 * live on a peer whose connection has just dropped, so completing the sink tries
		 * to call a socket that is already closed. That must not abandon the rest of the
		 * cleanup.
		 */
		@Test
		@DisplayName("close() completes every cleanup step even when a sink completion fails")
		@SuppressWarnings("unchecked")
		void shouldFinishCleanupWhenASinkCompletionFails() throws Exception {
			ClaudeAsyncClient client = ClaudeClient.async().workingDirectory(workingDirectory).build();

			Sinks.Many<Message> turnSink = mock(Sinks.Many.class);
			doThrow(new IllegalStateException("peer connection is gone")).when(turnSink).tryEmitComplete();
			Sinks.Many<ParsedMessage> rawSink = mock(Sinks.Many.class);
			doThrow(new IllegalStateException("peer connection is gone")).when(rawSink).tryEmitComplete();

			((AtomicReference<Sinks.Many<Message>>) readField(client, "currentTurnSink")).set(turnSink);
			setField(client, "rawMessageSink", rawSink);

			Thread shutdownHook = (Thread) readField(client, "shutdownHook");

			assertThatNoException().isThrownBy(client::close);

			// Both sinks were attempted - the first failure did not skip the second.
			verify(turnSink).tryEmitComplete();
			verify(rawSink).tryEmitComplete();

			// And the steps after them still ran: the sink field was cleared and the
			// shutdown hook - which pins this client for the life of the JVM - came off.
			assertThat(readField(client, "rawMessageSink")).isNull();
			assertThat(Runtime.getRuntime().removeShutdownHook(shutdownHook))
				.as("the shutdown hook should already have been removed by close()")
				.isFalse();
		}

		private Object readField(Object target, String name) throws Exception {
			return field(name).get(target);
		}

		private void setField(Object target, String name, Object value) throws Exception {
			field(name).set(target, value);
		}

		private Field field(String name) throws Exception {
			Field field = DefaultClaudeAsyncClient.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		}

	}

}
