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

public class XaiClientTest
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

	private XaiClient client(String apiKey)
	{
		return XaiClient.builder()
			.http(http)
			.gson(new Gson())
			.baseUrl(server.url("/"))
			.apiKey(apiKey)
			.model("grok-4-fast-reasoning")
			.maxTokens(1000)
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
			"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello there\","
				+ "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
				+ "{\"name\":\"get_grand_exchange_price\",\"arguments\":\"{\\\"item\\\":\\\"Abyssal whip\\\"}\"}}]},"
				+ "\"finish_reason\":\"tool_calls\"}],"
				+ "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7}}"));

		LlmResult result = client("secret-key").complete(
			new LlmRequest("sys", List.of(LlmMessage.userText("hi")), List.of(sampleTool())));

		// Response parsing.
		assertEquals("Hello there", result.getText());
		assertEquals(StopReason.TOOL_USE, result.getStopReason());
		assertTrue(result.wantsTools());
		assertEquals(1, result.getToolCalls().size());
		ToolCall call = result.getToolCalls().get(0);
		assertEquals("call_1", call.getId());
		assertEquals("get_grand_exchange_price", call.getName());
		assertEquals("Abyssal whip", call.getInput().get("item").getAsString());
		assertEquals(12, result.getInputTokens());
		assertEquals(7, result.getOutputTokens());

		// Request building.
		RecordedRequest request = server.takeRequest();
		assertEquals("/v1/chat/completions", request.getPath());
		assertEquals("Bearer secret-key", request.getHeader("authorization"));

		JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
		assertEquals("grok-4-fast-reasoning", body.get("model").getAsString());
		assertEquals(1000, body.get("max_tokens").getAsInt());

		JsonArray messages = body.getAsJsonArray("messages");
		assertEquals("system prompt becomes the first message", 2, messages.size());
		assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("sys", messages.get(0).getAsJsonObject().get("content").getAsString());
		assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
		assertEquals("hi", messages.get(1).getAsJsonObject().get("content").getAsString());

		JsonArray tools = body.getAsJsonArray("tools");
		assertEquals(1, tools.size());
		JsonObject function = tools.get(0).getAsJsonObject().getAsJsonObject("function");
		assertEquals("get_grand_exchange_price", function.get("name").getAsString());
		assertEquals("auto", body.get("tool_choice").getAsString());
	}

	@Test
	public void replaysToolCallsAndResultsInOpenAiShape() throws Exception
	{
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
			"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Done.\"},"
				+ "\"finish_reason\":\"stop\"}]}"));

		JsonObject input = new JsonObject();
		input.addProperty("item", "Abyssal whip");
		LlmMessage assistantToolCall = LlmMessage.assistant(
			List.of(new ToolUsePart("call_1", "get_grand_exchange_price", input, null)));
		LlmMessage toolResult = LlmMessage.toolResults(
			List.of(new ToolResult("call_1", "get_grand_exchange_price", "1,645,000 gp", false)));

		LlmResult result = client("secret-key").complete(new LlmRequest(
			null,
			List.of(LlmMessage.userText("how much is a whip?"), assistantToolCall, toolResult),
			List.of(sampleTool())));

		assertEquals("Done.", result.getText());
		assertEquals(StopReason.END_TURN, result.getStopReason());

		RecordedRequest request = server.takeRequest();
		JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
		JsonArray messages = body.getAsJsonArray("messages");
		// user, assistant(tool_calls), tool
		assertEquals(3, messages.size());

		JsonObject assistant = messages.get(1).getAsJsonObject();
		assertEquals("assistant", assistant.get("role").getAsString());
		JsonArray toolCalls = assistant.getAsJsonArray("tool_calls");
		assertEquals(1, toolCalls.size());
		JsonObject fn = toolCalls.get(0).getAsJsonObject().getAsJsonObject("function");
		// Arguments must cross the wire as a JSON string, not an object.
		assertEquals("Abyssal whip",
			JsonParser.parseString(fn.get("arguments").getAsString()).getAsJsonObject().get("item").getAsString());

		JsonObject toolMessage = messages.get(2).getAsJsonObject();
		assertEquals("tool", toolMessage.get("role").getAsString());
		assertEquals("call_1", toolMessage.get("tool_call_id").getAsString());
		assertEquals("1,645,000 gp", toolMessage.get("content").getAsString());
	}

	@Test
	public void omitsToolsWhenNoneProvided() throws Exception
	{
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
			"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));

		client("secret-key").complete(new LlmRequest(null, List.of(LlmMessage.userText("hi")), List.of()));

		RecordedRequest request = server.takeRequest();
		JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
		assertFalse("no tools field when no tools provided", body.has("tools"));
		assertFalse("no tool_choice when no tools provided", body.has("tool_choice"));
	}

	@Test
	public void throwsLlmExceptionOnHttpError()
	{
		server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"bad\"}}"));
		try
		{
			client("secret-key").complete(new LlmRequest(null, List.of(LlmMessage.userText("hi")), List.of()));
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
			client(null).complete(new LlmRequest(null, List.of(LlmMessage.userText("hi")), List.of()));
			fail("expected LlmException for missing key");
		}
		catch (LlmException e)
		{
			assertEquals(401, e.statusCode());
		}
		assertEquals("no request should be sent without a key", 0, server.getRequestCount());
	}
}
