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

public class GeminiClientTest
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

	private GeminiClient client(String apiKey)
	{
		return GeminiClient.builder()
			.http(http)
			.gson(new Gson())
			.baseUrl(server.url("/"))
			.apiKey(apiKey)
			.model("gemini-3.1-flash-lite")
			.maxTokens(1024)
			.build();
	}

	private static ToolSpec sampleTool()
	{
		// Includes additionalProperties (good for Anthropic strict mode) — must be stripped for Gemini.
		JsonObject prop = new JsonObject();
		prop.addProperty("type", "string");
		JsonObject properties = new JsonObject();
		properties.add("item", prop);
		JsonArray required = new JsonArray();
		required.add("item");
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		schema.addProperty("additionalProperties", false);
		return new ToolSpec("get_grand_exchange_price", "Price lookup", schema);
	}

	@Test
	public void buildsRequestAndParsesFunctionCall() throws Exception
	{
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
			"{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
				+ "{\"functionCall\":{\"name\":\"get_grand_exchange_price\",\"args\":{\"item\":\"Abyssal whip\"},\"id\":\"call-1\"},"
				+ "\"thoughtSignature\":\"SIG123\"}]},\"finishReason\":\"STOP\"}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}"));

		LlmResult result = client("k").complete(
			new LlmRequest("sys", List.of(LlmMessage.userText("how much is a whip?")), List.of(sampleTool())));

		assertEquals(StopReason.TOOL_USE, result.getStopReason());
		assertTrue(result.wantsTools());
		assertEquals(1, result.getToolCalls().size());
		ToolCall call = result.getToolCalls().get(0);
		assertEquals("call-1", call.getId());
		assertEquals("get_grand_exchange_price", call.getName());
		assertEquals("Abyssal whip", call.getInput().get("item").getAsString());
		assertEquals("SIG123", call.getSignature());
		assertEquals(10, result.getInputTokens());
		assertEquals(5, result.getOutputTokens());

		RecordedRequest request = server.takeRequest();
		assertTrue(request.getPath().startsWith("/v1beta/models/gemini-3.1-flash-lite:generateContent"));
		assertEquals("k", request.getHeader("x-goog-api-key"));

		JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
		assertEquals("sys", body.getAsJsonObject("system_instruction")
			.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
		JsonObject firstContent = body.getAsJsonArray("contents").get(0).getAsJsonObject();
		assertEquals("user", firstContent.get("role").getAsString());
		assertEquals("how much is a whip?", firstContent.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());

		JsonObject params = body.getAsJsonArray("tools").get(0).getAsJsonObject()
			.getAsJsonArray("function_declarations").get(0).getAsJsonObject()
			.getAsJsonObject("parameters");
		assertFalse("additionalProperties must be stripped for Gemini", params.has("additionalProperties"));
		assertTrue(params.has("properties"));
	}

	@Test
	public void parsesTextAndFinishReason() throws Exception
	{
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
			"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello there\"}]},\"finishReason\":\"STOP\"}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2}}"));

		LlmResult result = client("k").complete(new LlmRequest(null, List.of(LlmMessage.userText("hi")), List.of()));

		assertEquals("Hello there", result.getText());
		assertEquals(StopReason.END_TURN, result.getStopReason());
		assertTrue(result.getToolCalls().isEmpty());
	}

	@Test
	public void serialisesToolCallReplayWithSignatureAndFunctionResponse() throws Exception
	{
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
			"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}"));

		JsonObject args = new JsonObject();
		args.addProperty("item", "Abyssal whip");
		LlmMessage assistant = LlmMessage.assistant(List.of(
			new ToolUsePart("call-1", "get_grand_exchange_price", args, "SIG123")));
		LlmMessage toolResult = LlmMessage.toolResults(List.of(
			new ToolResult("call-1", "get_grand_exchange_price", "Abyssal whip ~1.6m gp", false)));

		client("k").complete(new LlmRequest(null,
			List.of(LlmMessage.userText("how much?"), assistant, toolResult), List.of(sampleTool())));

		JsonObject body = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
		JsonArray contents = body.getAsJsonArray("contents");
		assertEquals(3, contents.size());

		// The assistant (model) turn replays the functionCall + the opaque thoughtSignature.
		JsonObject modelPart = contents.get(1).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject();
		assertEquals("model", contents.get(1).getAsJsonObject().get("role").getAsString());
		assertEquals("get_grand_exchange_price", modelPart.getAsJsonObject("functionCall").get("name").getAsString());
		assertEquals("SIG123", modelPart.get("thoughtSignature").getAsString());

		// The tool result becomes a functionResponse.
		JsonObject respPart = contents.get(2).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject();
		JsonObject functionResponse = respPart.getAsJsonObject("functionResponse");
		assertEquals("get_grand_exchange_price", functionResponse.get("name").getAsString());
		assertEquals("Abyssal whip ~1.6m gp", functionResponse.getAsJsonObject("response").get("result").getAsString());
	}

	@Test
	public void throwsOnHttpError()
	{
		server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"bad request\"}}"));
		try
		{
			client("k").complete(new LlmRequest(null, List.of(LlmMessage.userText("hi")), List.of()));
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
		assertEquals(0, server.getRequestCount());
	}
}
