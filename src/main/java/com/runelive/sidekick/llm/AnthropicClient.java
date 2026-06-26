package com.runelive.sidekick.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.runelive.sidekick.http.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * {@link LlmClient} for the Anthropic Messages API, over raw HTTP (OkHttp + Gson).
 *
 * <p>Raw HTTP rather than the official SDK keeps the agent core portable into a RuneLite hub plugin
 * (which reuses the injected {@code OkHttpClient}/{@code Gson} and avoids the SDK's dependency tree)
 * and makes the wire format trivial to mock. The neutral {@link ContentPart} model is translated
 * to Anthropic content blocks here.
 */
@Slf4j
public class AnthropicClient implements LlmClient
{
	private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl baseUrl;
	private final String apiKey;
	private final String model;
	private final long maxTokens;
	private final boolean thinking;
	private final String userAgent;

	@Builder
	public AnthropicClient(
		OkHttpClient http,
		Gson gson,
		HttpUrl baseUrl,
		String apiKey,
		String model,
		long maxTokens,
		boolean thinking,
		String userAgent)
	{
		this.http = http;
		this.gson = gson;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.model = model;
		this.maxTokens = maxTokens;
		this.thinking = thinking;
		this.userAgent = userAgent;
	}

	@Override
	public LlmResult complete(LlmRequest request)
	{
		if (apiKey == null || apiKey.trim().isEmpty())
		{
			throw new LlmException(401, "Anthropic API key is not set.");
		}

		JsonObject body = buildBody(request);
		HttpUrl url = baseUrl.newBuilder().addPathSegments("v1/messages").build();
		Request httpRequest = new Request.Builder()
			.url(url)
			.header("x-api-key", apiKey)
			.header("anthropic-version", ANTHROPIC_VERSION)
			.header("content-type", "application/json")
			.header("accept", "application/json")
			.header("user-agent", userAgent)
			.post(RequestBody.create(gson.toJson(body), JSON_MEDIA))
			.build();

		try (Response response = http.newCall(httpRequest).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				throw new LlmException(response.code(),
					"Anthropic API returned HTTP " + response.code() + ": " + truncate(responseBody));
			}
			return parse(responseBody);
		}
		catch (IOException e)
		{
			throw new LlmException(0, "Anthropic API request failed: " + e.getMessage(), e);
		}
	}

	private JsonObject buildBody(LlmRequest request)
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", maxTokens);
		if (request.getSystem() != null && !request.getSystem().isEmpty())
		{
			body.addProperty("system", request.getSystem());
		}

		JsonArray messages = new JsonArray();
		for (LlmMessage message : request.getMessages())
		{
			JsonObject obj = new JsonObject();
			obj.addProperty("role", message.getRole() == Role.ASSISTANT ? "assistant" : "user");
			obj.add("content", toContentBlocks(message));
			messages.add(obj);
		}
		body.add("messages", messages);

		if (request.getTools() != null && !request.getTools().isEmpty())
		{
			JsonArray tools = new JsonArray();
			for (ToolSpec spec : request.getTools())
			{
				JsonObject tool = new JsonObject();
				tool.addProperty("name", spec.getName());
				tool.addProperty("description", spec.getDescription());
				tool.add("input_schema", spec.getInputSchema());
				tools.add(tool);
			}
			body.add("tools", tools);
		}

		if (thinking)
		{
			JsonObject thinkingConfig = new JsonObject();
			thinkingConfig.addProperty("type", "adaptive");
			body.add("thinking", thinkingConfig);
		}

		return body;
	}

	private JsonArray toContentBlocks(LlmMessage message)
	{
		JsonArray blocks = new JsonArray();
		for (ContentPart part : message.getParts())
		{
			if (part instanceof TextPart)
			{
				JsonObject block = new JsonObject();
				block.addProperty("type", "text");
				block.addProperty("text", ((TextPart) part).getText());
				blocks.add(block);
			}
			else if (part instanceof ToolUsePart)
			{
				ToolUsePart tool = (ToolUsePart) part;
				JsonObject block = new JsonObject();
				block.addProperty("type", "tool_use");
				block.addProperty("id", tool.getId());
				block.addProperty("name", tool.getName());
				block.add("input", tool.getInput());
				blocks.add(block);
			}
			else if (part instanceof ToolResultPart)
			{
				ToolResultPart result = (ToolResultPart) part;
				JsonObject block = new JsonObject();
				block.addProperty("type", "tool_result");
				block.addProperty("tool_use_id", result.getToolUseId());
				block.addProperty("content", result.getContent());
				if (result.isError())
				{
					block.addProperty("is_error", true);
				}
				blocks.add(block);
			}
		}
		return blocks;
	}

	private LlmResult parse(String responseBody)
	{
		JsonObject root;
		try
		{
			JsonElement element = JsonParser.parseString(responseBody);
			if (!element.isJsonObject())
			{
				throw new LlmException(0, "Anthropic API returned a non-object response");
			}
			root = element.getAsJsonObject();
		}
		catch (JsonParseException e)
		{
			throw new LlmException(0, "Anthropic API returned invalid JSON", e);
		}

		JsonArray content = root.has("content") && root.get("content").isJsonArray()
			? root.getAsJsonArray("content")
			: new JsonArray();

		StringBuilder text = new StringBuilder();
		List<ToolCall> toolCalls = new ArrayList<>();
		for (JsonElement element : content)
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			JsonObject block = element.getAsJsonObject();
			String type = Json.optString(block, "type", "");
			if ("text".equals(type))
			{
				text.append(Json.optString(block, "text", ""));
			}
			else if ("tool_use".equals(type))
			{
				JsonObject input = block.has("input") && block.get("input").isJsonObject()
					? block.getAsJsonObject("input")
					: new JsonObject();
				toolCalls.add(new ToolCall(
					Json.optString(block, "id", ""),
					Json.optString(block, "name", ""),
					input,
					null));
			}
		}

		StopReason stopReason = StopReason.fromWire(Json.optString(root, "stop_reason", null));
		JsonObject usage = Json.optObject(root, "usage");
		int inputTokens = usage == null ? 0 : Json.optInt(usage, "input_tokens", 0);
		int outputTokens = usage == null ? 0 : Json.optInt(usage, "output_tokens", 0);

		return new LlmResult(stopReason, text.toString().trim(), toolCalls, inputTokens, outputTokens);
	}

	private static String truncate(String body)
	{
		return body.length() > 500 ? body.substring(0, 500) + "..." : body;
	}
}
