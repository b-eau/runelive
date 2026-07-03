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
 * {@link LlmClient} for xAI's Grok models via the OpenAI-compatible Chat Completions API
 * ({@code /v1/chat/completions}), over raw HTTP (OkHttp + Gson).
 *
 * <p>Reuses the same provider-neutral {@link ContentPart} model the agent uses for Anthropic and
 * Gemini, translating it to OpenAI's {@code messages}/{@code tool_calls} shape here. Tool-call
 * arguments cross the wire as a JSON <em>string</em> (OpenAI's quirk), so they are serialised on the
 * way out and parsed back into a {@link JsonObject} on the way in.
 */
@Slf4j
public class XaiClient implements LlmClient
{
	private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl baseUrl;
	private final String apiKey;
	private final String model;
	private final long maxTokens;

	@Builder
	public XaiClient(OkHttpClient http, Gson gson, HttpUrl baseUrl, String apiKey, String model, long maxTokens)
	{
		this.http = http;
		this.gson = gson;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.model = model;
		this.maxTokens = maxTokens;
	}

	@Override
	public LlmResult complete(LlmRequest request)
	{
		if (apiKey == null || apiKey.trim().isEmpty())
		{
			throw new LlmException(401, "xAI API key is not set.");
		}

		JsonObject body = buildBody(request);
		HttpUrl url = baseUrl.newBuilder().addPathSegments("v1/chat/completions").build();
		Request httpRequest = new Request.Builder()
			.url(url)
			.header("authorization", "Bearer " + apiKey)
			.header("content-type", "application/json")
			.header("accept", "application/json")
			.post(RequestBody.create(JSON_MEDIA, gson.toJson(body)))
			.build();

		try (Response response = http.newCall(httpRequest).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				throw new LlmException(response.code(),
					"xAI API returned HTTP " + response.code() + ": " + truncate(responseBody));
			}
			return parse(responseBody);
		}
		catch (IOException e)
		{
			throw new LlmException(0, "xAI API request failed: " + e.getMessage(), e);
		}
	}

	private JsonObject buildBody(LlmRequest request)
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", maxTokens);

		JsonArray messages = new JsonArray();
		if (request.getSystem() != null && !request.getSystem().isEmpty())
		{
			JsonObject sys = new JsonObject();
			sys.addProperty("role", "system");
			sys.addProperty("content", request.getSystem());
			messages.add(sys);
		}
		for (LlmMessage message : request.getMessages())
		{
			appendMessage(messages, message);
		}
		body.add("messages", messages);

		if (request.getTools() != null && !request.getTools().isEmpty())
		{
			JsonArray tools = new JsonArray();
			for (ToolSpec spec : request.getTools())
			{
				JsonObject function = new JsonObject();
				function.addProperty("name", spec.getName());
				function.addProperty("description", spec.getDescription());
				function.add("parameters", spec.getInputSchema());

				JsonObject tool = new JsonObject();
				tool.addProperty("type", "function");
				tool.add("function", function);
				tools.add(tool);
			}
			body.add("tools", tools);
			body.addProperty("tool_choice", "auto");
		}

		return body;
	}

	/** Translates one neutral message into one or more OpenAI chat messages. */
	private void appendMessage(JsonArray messages, LlmMessage message)
	{
		StringBuilder text = new StringBuilder();
		JsonArray toolCalls = new JsonArray();
		List<ToolResultPart> toolResults = new ArrayList<>();

		for (ContentPart part : message.getParts())
		{
			if (part instanceof TextPart)
			{
				text.append(((TextPart) part).getText());
			}
			else if (part instanceof ToolUsePart)
			{
				ToolUsePart tool = (ToolUsePart) part;
				JsonObject function = new JsonObject();
				function.addProperty("name", tool.getName());
				function.addProperty("arguments",
					gson.toJson(tool.getInput() == null ? new JsonObject() : tool.getInput()));

				JsonObject call = new JsonObject();
				call.addProperty("id", tool.getId());
				call.addProperty("type", "function");
				call.add("function", function);
				toolCalls.add(call);
			}
			else if (part instanceof ToolResultPart)
			{
				toolResults.add((ToolResultPart) part);
			}
		}

		// Tool results become standalone {role:"tool"} messages, one per executed call.
		for (ToolResultPart result : toolResults)
		{
			JsonObject toolMessage = new JsonObject();
			toolMessage.addProperty("role", "tool");
			toolMessage.addProperty("tool_call_id", result.getToolUseId());
			toolMessage.addProperty("content", result.getContent());
			messages.add(toolMessage);
		}

		if (message.getRole() == Role.ASSISTANT)
		{
			JsonObject assistant = new JsonObject();
			assistant.addProperty("role", "assistant");
			if (text.length() > 0)
			{
				assistant.addProperty("content", text.toString());
			}
			if (toolCalls.size() > 0)
			{
				assistant.add("tool_calls", toolCalls);
			}
			// An assistant turn with neither text nor tool calls is meaningless; skip it.
			if (text.length() > 0 || toolCalls.size() > 0)
			{
				messages.add(assistant);
			}
		}
		else if (toolResults.isEmpty() && text.length() > 0)
		{
			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			user.addProperty("content", text.toString());
			messages.add(user);
		}
	}

	private LlmResult parse(String responseBody)
	{
		JsonObject root;
		try
		{
			JsonElement element = new JsonParser().parse(responseBody);
			if (!element.isJsonObject())
			{
				throw new LlmException(0, "xAI API returned a non-object response");
			}
			root = element.getAsJsonObject();
		}
		catch (JsonParseException e)
		{
			throw new LlmException(0, "xAI API returned invalid JSON", e);
		}

		JsonObject usage = Json.optObject(root, "usage");
		int inputTokens = usage == null ? 0 : Json.optInt(usage, "prompt_tokens", 0);
		int outputTokens = usage == null ? 0 : Json.optInt(usage, "completion_tokens", 0);

		JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
			? root.getAsJsonArray("choices")
			: new JsonArray();
		if (choices.size() == 0)
		{
			return new LlmResult(StopReason.OTHER, "", new ArrayList<>(), inputTokens, outputTokens);
		}

		JsonObject choice = choices.get(0).getAsJsonObject();
		JsonObject choiceMessage = Json.optObject(choice, "message");

		String text = choiceMessage == null ? "" : Json.optString(choiceMessage, "content", "");
		List<ToolCall> toolCalls = new ArrayList<>();
		if (choiceMessage != null && choiceMessage.has("tool_calls") && choiceMessage.get("tool_calls").isJsonArray())
		{
			for (JsonElement element : choiceMessage.getAsJsonArray("tool_calls"))
			{
				if (!element.isJsonObject())
				{
					continue;
				}
				JsonObject call = element.getAsJsonObject();
				JsonObject function = Json.optObject(call, "function");
				if (function == null)
				{
					continue;
				}
				String id = Json.optString(call, "id", "");
				String name = Json.optString(function, "name", "");
				JsonObject args = parseArguments(Json.optString(function, "arguments", ""));
				toolCalls.add(new ToolCall(id, name, args, null));
			}
		}

		StopReason stopReason = toolCalls.isEmpty()
			? mapFinishReason(Json.optString(choice, "finish_reason", null))
			: StopReason.TOOL_USE;

		return new LlmResult(stopReason, text == null ? "" : text.trim(), toolCalls, inputTokens, outputTokens);
	}

	private JsonObject parseArguments(String arguments)
	{
		if (arguments == null || arguments.trim().isEmpty())
		{
			return new JsonObject();
		}
		try
		{
			JsonElement element = new JsonParser().parse(arguments);
			return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
		}
		catch (JsonParseException e)
		{
			log.debug("xAI tool-call arguments were not valid JSON: {}", truncate(arguments));
			return new JsonObject();
		}
	}

	private static StopReason mapFinishReason(String finishReason)
	{
		if (finishReason == null)
		{
			return StopReason.OTHER;
		}
		switch (finishReason)
		{
			case "stop":
				return StopReason.END_TURN;
			case "length":
				return StopReason.MAX_TOKENS;
			case "tool_calls":
				return StopReason.TOOL_USE;
			case "content_filter":
				return StopReason.REFUSAL;
			default:
				return StopReason.OTHER;
		}
	}

	private static String truncate(String body)
	{
		return body.length() > 500 ? body.substring(0, 500) + "..." : body;
	}
}
