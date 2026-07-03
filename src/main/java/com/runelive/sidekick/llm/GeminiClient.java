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
 * {@link LlmClient} for Google's Gemini API ({@code generateContent}), over raw HTTP (OkHttp + Gson).
 *
 * <p>Demonstrates the provider-neutral seam: the same {@link LlmRequest}/{@link LlmResult} the agent
 * uses for Anthropic is translated here to Gemini's {@code contents}/{@code functionCall} shape.
 * Gemini 3 returns a {@code thoughtSignature} on tool-call parts which must be replayed on the next
 * request; that round-trips via {@link ToolCall#getSignature()} → {@link ToolUsePart#getSignature()}.
 */
@Slf4j
public class GeminiClient implements LlmClient
{
	private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl baseUrl;
	private final String apiKey;
	private final String model;
	private final long maxTokens;

	@Builder
	public GeminiClient(OkHttpClient http, Gson gson, HttpUrl baseUrl, String apiKey, String model, long maxTokens)
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
			throw new LlmException(401, "Gemini API key is not set.");
		}

		JsonObject body = buildBody(request);
		HttpUrl url = baseUrl.newBuilder()
			.addEncodedPathSegments("v1beta/models/" + model + ":generateContent")
			.build();
		Request httpRequest = new Request.Builder()
			.url(url)
			// API key goes in a header, not the URL, so it never lands in logs/error messages.
			.header("x-goog-api-key", apiKey)
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
					"Gemini API returned HTTP " + response.code() + ": " + truncate(responseBody));
			}
			return parse(responseBody);
		}
		catch (IOException e)
		{
			throw new LlmException(0, "Gemini API request failed: " + e.getMessage(), e);
		}
	}

	private JsonObject buildBody(LlmRequest request)
	{
		JsonObject body = new JsonObject();

		if (request.getSystem() != null && !request.getSystem().isEmpty())
		{
			JsonObject sysText = new JsonObject();
			sysText.addProperty("text", request.getSystem());
			JsonArray sysParts = new JsonArray();
			sysParts.add(sysText);
			JsonObject sys = new JsonObject();
			sys.add("parts", sysParts);
			body.add("system_instruction", sys);
		}

		JsonArray contents = new JsonArray();
		for (LlmMessage message : request.getMessages())
		{
			JsonObject content = new JsonObject();
			content.addProperty("role", message.getRole() == Role.ASSISTANT ? "model" : "user");
			content.add("parts", toParts(message));
			contents.add(content);
		}
		body.add("contents", contents);

		if (request.getTools() != null && !request.getTools().isEmpty())
		{
			JsonArray declarations = new JsonArray();
			for (ToolSpec spec : request.getTools())
			{
				JsonObject decl = new JsonObject();
				decl.addProperty("name", spec.getName());
				decl.addProperty("description", spec.getDescription());
				decl.add("parameters", sanitizeSchema(spec.getInputSchema()));
				declarations.add(decl);
			}
			JsonObject tool = new JsonObject();
			tool.add("function_declarations", declarations);
			JsonArray tools = new JsonArray();
			tools.add(tool);
			body.add("tools", tools);
		}

		JsonObject generationConfig = new JsonObject();
		generationConfig.addProperty("maxOutputTokens", maxTokens);
		body.add("generationConfig", generationConfig);

		return body;
	}

	private JsonArray toParts(LlmMessage message)
	{
		JsonArray parts = new JsonArray();
		for (ContentPart part : message.getParts())
		{
			if (part instanceof TextPart)
			{
				JsonObject p = new JsonObject();
				p.addProperty("text", ((TextPart) part).getText());
				parts.add(p);
			}
			else if (part instanceof ToolUsePart)
			{
				ToolUsePart tool = (ToolUsePart) part;
				JsonObject functionCall = new JsonObject();
				functionCall.addProperty("name", tool.getName());
				functionCall.add("args", tool.getInput() == null ? new JsonObject() : tool.getInput());
				if (tool.getId() != null && !tool.getId().isEmpty())
				{
					functionCall.addProperty("id", tool.getId());
				}
				JsonObject p = new JsonObject();
				p.add("functionCall", functionCall);
				// Gemini 3 requires the opaque thought signature to be replayed on the tool-call part.
				if (tool.getSignature() != null && !tool.getSignature().isEmpty())
				{
					p.addProperty("thoughtSignature", tool.getSignature());
				}
				parts.add(p);
			}
			else if (part instanceof ToolResultPart)
			{
				ToolResultPart result = (ToolResultPart) part;
				JsonObject response = new JsonObject();
				response.addProperty(result.isError() ? "error" : "result", result.getContent());
				JsonObject functionResponse = new JsonObject();
				functionResponse.addProperty("name", result.getName());
				if (result.getToolUseId() != null && !result.getToolUseId().isEmpty())
				{
					functionResponse.addProperty("id", result.getToolUseId());
				}
				functionResponse.add("response", response);
				JsonObject p = new JsonObject();
				p.add("functionResponse", functionResponse);
				parts.add(p);
			}
		}
		return parts;
	}

	private LlmResult parse(String responseBody)
	{
		JsonObject root;
		try
		{
			JsonElement element = new JsonParser().parse(responseBody);
			if (!element.isJsonObject())
			{
				throw new LlmException(0, "Gemini API returned a non-object response");
			}
			root = element.getAsJsonObject();
		}
		catch (JsonParseException e)
		{
			throw new LlmException(0, "Gemini API returned invalid JSON", e);
		}

		JsonObject usage = Json.optObject(root, "usageMetadata");
		int inputTokens = usage == null ? 0 : Json.optInt(usage, "promptTokenCount", 0);
		int outputTokens = usage == null ? 0 : Json.optInt(usage, "candidatesTokenCount", 0);

		JsonArray candidates = root.has("candidates") && root.get("candidates").isJsonArray()
			? root.getAsJsonArray("candidates")
			: new JsonArray();
		if (candidates.size() == 0)
		{
			// Blocked prompt (or empty) — surface as a refusal so the agent can respond gracefully.
			JsonObject feedback = Json.optObject(root, "promptFeedback");
			String blocked = feedback == null ? null : Json.optString(feedback, "blockReason", null);
			return new LlmResult(blocked != null ? StopReason.REFUSAL : StopReason.OTHER, "",
				new ArrayList<>(), inputTokens, outputTokens);
		}

		JsonObject candidate = candidates.get(0).getAsJsonObject();
		JsonObject content = Json.optObject(candidate, "content");
		JsonArray parts = content != null && content.has("parts") && content.get("parts").isJsonArray()
			? content.getAsJsonArray("parts")
			: new JsonArray();

		StringBuilder text = new StringBuilder();
		List<ToolCall> toolCalls = new ArrayList<>();
		for (JsonElement element : parts)
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			JsonObject part = element.getAsJsonObject();
			if (part.has("text") && !part.get("text").isJsonNull())
			{
				text.append(part.get("text").getAsString());
			}
			JsonObject functionCall = Json.optObject(part, "functionCall");
			if (functionCall != null)
			{
				String name = Json.optString(functionCall, "name", "");
				String id = Json.optString(functionCall, "id", name);
				JsonObject args = Json.optObject(functionCall, "args");
				String signature = Json.optString(part, "thoughtSignature", null);
				toolCalls.add(new ToolCall(id, name, args == null ? new JsonObject() : args, signature));
			}
		}

		StopReason stopReason;
		if (!toolCalls.isEmpty())
		{
			stopReason = StopReason.TOOL_USE;
		}
		else
		{
			stopReason = mapFinishReason(Json.optString(candidate, "finishReason", null));
		}

		return new LlmResult(stopReason, text.toString().trim(), toolCalls, inputTokens, outputTokens);
	}

	private static StopReason mapFinishReason(String finishReason)
	{
		if (finishReason == null)
		{
			return StopReason.OTHER;
		}
		switch (finishReason)
		{
			case "STOP":
				return StopReason.END_TURN;
			case "MAX_TOKENS":
				return StopReason.MAX_TOKENS;
			case "SAFETY":
			case "RECITATION":
			case "BLOCKLIST":
			case "PROHIBITED_CONTENT":
			case "SPII":
				return StopReason.REFUSAL;
			default:
				return StopReason.OTHER;
		}
	}

	/**
	 * Gemini's function parameters use an OpenAPI-subset schema that rejects {@code additionalProperties}.
	 * Our tool schemas include it (good practice for Anthropic strict mode), so strip it here.
	 */
	private static JsonElement sanitizeSchema(JsonObject schema)
	{
		JsonObject copy = schema.deepCopy();
		stripKey(copy, "additionalProperties");
		return copy;
	}

	private static void stripKey(JsonElement element, String key)
	{
		if (element.isJsonObject())
		{
			JsonObject obj = element.getAsJsonObject();
			obj.remove(key);
			for (java.util.Map.Entry<String, JsonElement> entry : obj.entrySet())
			{
				stripKey(entry.getValue(), key);
			}
		}
		else if (element.isJsonArray())
		{
			for (JsonElement child : element.getAsJsonArray())
			{
				stripKey(child, key);
			}
		}
	}

	private static String truncate(String body)
	{
		return body.length() > 500 ? body.substring(0, 500) + "..." : body;
	}
}
