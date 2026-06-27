package com.runelive.sidekick.voice;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.runelive.sidekick.http.Json;
import com.runelive.sidekick.llm.LlmException;
import java.io.IOException;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Gemini-backed speech-to-text (STT) and text-to-speech (TTS).
 *
 * <p>STT: encodes a WAV file as inline base64 and asks {@code gemini-2.0-flash} to transcribe it.
 * TTS: sends the response text to {@code gemini-2.5-flash-preview-tts} and decodes the returned
 * PCM audio (24 kHz, 16-bit, mono, little-endian).
 *
 * <p>All methods run synchronously and must be called from a background thread.
 */
@Slf4j
public class GeminiVoiceClient
{
	private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
	private static final String STT_MODEL = "gemini-2.0-flash";
	private static final String TTS_MODEL = "gemini-2.5-flash-preview-tts";

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl baseUrl;
	private final String apiKey;
	private final String ttsVoice;

	public GeminiVoiceClient(OkHttpClient http, Gson gson, HttpUrl baseUrl, String apiKey, String ttsVoice)
	{
		this.http = http;
		this.gson = gson;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.ttsVoice = ttsVoice;
	}

	/**
	 * Transcribes WAV audio to text.
	 *
	 * @return the transcribed words, or an empty string if nothing was recognised
	 */
	String transcribe(byte[] wavBytes) throws IOException
	{
		String b64 = Base64.getEncoder().encodeToString(wavBytes);

		JsonObject inlineData = new JsonObject();
		inlineData.addProperty("mimeType", "audio/wav");
		inlineData.addProperty("data", b64);

		JsonObject audioPart = new JsonObject();
		audioPart.add("inlineData", inlineData);

		JsonObject promptPart = new JsonObject();
		promptPart.addProperty("text",
			"Transcribe this audio exactly as spoken. Return only the transcription text, nothing else.");

		JsonArray parts = new JsonArray();
		parts.add(promptPart);
		parts.add(audioPart);

		JsonObject userContent = new JsonObject();
		userContent.addProperty("role", "user");
		userContent.add("parts", parts);

		JsonArray contents = new JsonArray();
		contents.add(userContent);

		JsonObject genConfig = new JsonObject();
		genConfig.addProperty("temperature", 0);
		genConfig.addProperty("maxOutputTokens", 256);

		JsonObject body = new JsonObject();
		body.add("contents", contents);
		body.add("generationConfig", genConfig);

		JsonObject response = post(STT_MODEL, body);
		return extractText(response);
	}

	/**
	 * Synthesises speech from text.
	 *
	 * @return raw 16-bit signed PCM at 24 kHz mono (little-endian), ready for {@link AudioPlayer}
	 */
	byte[] synthesize(String text) throws IOException
	{
		JsonObject promptPart = new JsonObject();
		promptPart.addProperty("text", text);

		JsonArray parts = new JsonArray();
		parts.add(promptPart);

		JsonObject userContent = new JsonObject();
		userContent.addProperty("role", "user");
		userContent.add("parts", parts);

		JsonArray contents = new JsonArray();
		contents.add(userContent);

		JsonObject prebuiltVoice = new JsonObject();
		prebuiltVoice.addProperty("voiceName", ttsVoice);

		JsonObject voiceConfig = new JsonObject();
		voiceConfig.add("prebuiltVoiceConfig", prebuiltVoice);

		JsonObject speechConfig = new JsonObject();
		speechConfig.add("voiceConfig", voiceConfig);

		JsonArray modalities = new JsonArray();
		modalities.add("AUDIO");

		JsonObject genConfig = new JsonObject();
		genConfig.add("responseModalities", modalities);
		genConfig.add("speechConfig", speechConfig);

		JsonObject body = new JsonObject();
		body.add("contents", contents);
		body.add("generationConfig", genConfig);

		JsonObject response = post(TTS_MODEL, body);
		return extractPcm(response);
	}

	private JsonObject post(String model, JsonObject body) throws IOException
	{
		HttpUrl url = baseUrl.newBuilder()
			.addEncodedPathSegments("v1beta/models/" + model + ":generateContent")
			.build();
		Request request = new Request.Builder()
			.url(url)
			.header("x-goog-api-key", apiKey)
			.header("content-type", "application/json")
			.header("accept", "application/json")
			.post(RequestBody.create(JSON_MT, gson.toJson(body)))
			.build();

		try (Response response = http.newCall(request).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				throw new LlmException(response.code(),
					"Gemini voice API HTTP " + response.code() + ": " + truncate(responseBody));
			}
			JsonElement element = new JsonParser().parse(responseBody);
			if (!element.isJsonObject())
			{
				throw new LlmException(0, "Gemini voice API returned non-object");
			}
			return element.getAsJsonObject();
		}
	}

	private static String extractText(JsonObject root)
	{
		JsonArray candidates = root.has("candidates") ? root.getAsJsonArray("candidates") : new JsonArray();
		if (candidates.size() == 0)
		{
			return "";
		}
		JsonObject content = Json.optObject(candidates.get(0).getAsJsonObject(), "content");
		if (content == null)
		{
			return "";
		}
		JsonArray parts = content.has("parts") ? content.getAsJsonArray("parts") : new JsonArray();
		StringBuilder sb = new StringBuilder();
		for (JsonElement el : parts)
		{
			if (el.isJsonObject() && el.getAsJsonObject().has("text"))
			{
				sb.append(el.getAsJsonObject().get("text").getAsString());
			}
		}
		return sb.toString().trim();
	}

	private static byte[] extractPcm(JsonObject root)
	{
		JsonArray candidates = root.has("candidates") ? root.getAsJsonArray("candidates") : new JsonArray();
		if (candidates.size() == 0)
		{
			throw new LlmException(0, "Gemini TTS returned no candidates");
		}
		JsonObject content = Json.optObject(candidates.get(0).getAsJsonObject(), "content");
		if (content == null)
		{
			throw new LlmException(0, "Gemini TTS response has no content");
		}
		JsonArray parts = content.has("parts") ? content.getAsJsonArray("parts") : new JsonArray();
		for (JsonElement el : parts)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject inlineData = Json.optObject(el.getAsJsonObject(), "inlineData");
			if (inlineData != null && inlineData.has("data"))
			{
				return Base64.getDecoder().decode(inlineData.get("data").getAsString());
			}
		}
		throw new LlmException(0, "Gemini TTS response contained no audio data");
	}

	private static String truncate(String s)
	{
		return s.length() > 300 ? s.substring(0, 300) + "..." : s;
	}
}
