package com.runelive.sidekick.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AnthropicClientTest
{
	private MockWebServer server;
	private OkHttpClient http;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		http = new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(10)).build();
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	private AnthropicClient client(boolean thinking, String apiKey)
	{
		return AnthropicClient.builder()
			.http(http)
			.gson(new Gson())
			.baseUrl(server.url("/"))
			.apiKey(apiKey)
			.model("claude-opus-4-8")
			.maxTokens(1000)
			.thinking(thinking)
			.userAgent("test-agent")
			.build();
	}

	private static ToolSpec sampleTool()
	{
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		return new ToolSpec("get_grand_exchange_price", "Price lookup", schema);
	}

	@Test
	public void buildsRequestAndParsesTextAndToolUse() throws Exception
	{
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
			"{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":["
				+ "{\"type\":\"text\",\"text\":\"Hello there\"},"
				+ "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_grand_exchange_price\",\"input\":{\"item\":\"Abyssal whip\"}}],"
				+ "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":12,\"output_tokens\":7}}"));

		LlmResult result = client(false, "secret-key").complete(
			new LlmRequest("sys", List.of(LlmMessage.userText("hi")), List.of(sampleTool())));

		// Response parsing.
		assertEquals("Hello there", result.getText());
		assertEquals(StopReason.TOOL_USE, result.getStopReason());
		assertTrue(result.wantsTools());
		assertEquals(1, result.getToolCalls().size());
		ToolCall call = result.getToolCalls().get(0);
		assertEquals("toolu_1", call.getId());
		assertEquals("get_grand_exchange_price", call.getName());
		assertEquals("Abyssal whip", call.getInput().get("item").getAsString());
		assertEquals(12, result.getInputTokens());
		assertEquals(7, result.getOutputTokens());

		// Request building.
		RecordedRequest request = server.takeRequest();
		assertEquals("/v1/messages", request.getPath());
		assertEquals("secret-key", request.getHeader("x-api-key"));
		assertEquals("2023-06-01", request.getHeader("anthropic-version"));

		JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
		assertEquals("claude-opus-4-8", body.get("model").getAsString());
		assertEquals(1000, body.get("max_tokens").getAsInt());
		assertEquals("sys", body.get("system").getAsString());
		assertFalse("thinking omitted when disabled", body.has("thinking"));

		JsonArray messages = body.getAsJsonArray("messages");
		assertEquals(1, messages.size());
		JsonObject first = messages.get(0).getAsJsonObject();
		assertEquals("user", first.get("role").getAsString());
		JsonArray firstContent = first.getAsJsonArray("content");
		assertEquals("text", firstContent.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("hi", firstContent.get(0).getAsJsonObject().get("text").getAsString());

		JsonArray tools = body.getAsJsonArray("tools");
		assertEquals(1, tools.size());
		assertEquals("get_grand_exchange_price", tools.get(0).getAsJsonObject().get("name").getAsString());
	}

	@Test
	public void includesAdaptiveThinkingWhenEnabled() throws Exception
	{
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
			"{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}"));

		client(true, "secret-key").complete(new LlmRequest(null, List.of(LlmMessage.userText("hi")), List.of()));

		RecordedRequest request = server.takeRequest();
		JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
		assertTrue(body.has("thinking"));
		assertEquals("adaptive", body.getAsJsonObject("thinking").get("type").getAsString());
		assertFalse("no tools field when no tools provided", body.has("tools"));
	}

	@Test
	public void throwsLlmExceptionOnHttpError()
	{
		server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"bad\"}}"));
		try
		{
			client(false, "secret-key").complete(new LlmRequest(null, List.of(LlmMessage.userText("hi")), List.of()));
			fail("expected LlmException");
		}
		catch (LlmException e)
		{
			assertEquals(400, e.statusCode());
		}
	}

	@Test
	public void failsFastWhenApiKeyMissing()
	{
		try
		{
			client(false, null).complete(new LlmRequest(null, List.of(LlmMessage.userText("hi")), List.of()));
			fail("expected LlmException for missing key");
		}
		catch (LlmException e)
		{
			assertEquals(401, e.statusCode());
		}
		assertEquals("no request should be sent without a key", 0, server.getRequestCount());
	}
}
