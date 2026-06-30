package com.runelive.sidekick.voice.realtime;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.runelive.sidekick.http.Json;
import com.runelive.sidekick.llm.ToolSpec;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * A {@link RealtimeVoiceSession} that speaks the OpenAI Realtime API protocol over an OkHttp
 * WebSocket. It is used here for xAI Grok (pointed at xAI's realtime endpoint), and the same class
 * serves OpenAI Realtime by swapping the base URL; Gemini Live would be a separate implementation.
 *
 * <p><b>Protocol note:</b> the event shapes below follow the OpenAI Realtime API. xAI's exact wire
 * format could not be verified offline, so the endpoint, model name and event details may need
 * adjustment once confirmed against live xAI docs. The wire format is deliberately isolated here so
 * only this class changes. All callbacks run on the OkHttp websocket thread; tool calls are executed
 * on a separate thread so the read loop is never blocked.
 */
@Slf4j
public class OpenAiRealtimeSession implements RealtimeVoiceSession
{
	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl baseUrl;
	private final String apiKey;
	private final boolean sendBetaHeader;

	private final ExecutorService toolExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "sidekick-realtime-tools");
		t.setDaemon(true);
		return t;
	});

	private volatile WebSocket socket;
	private volatile RealtimeSessionConfig config;
	private volatile RealtimeSessionListener listener;
	private final StringBuilder assistantText = new StringBuilder();

	public OpenAiRealtimeSession(OkHttpClient http, Gson gson, HttpUrl baseUrl, String apiKey,
		boolean sendBetaHeader)
	{
		this.http = http;
		this.gson = gson;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.sendBetaHeader = sendBetaHeader;
	}

	@Override
	public void connect(RealtimeSessionConfig config, RealtimeSessionListener listener)
	{
		this.config = config;
		this.listener = listener;

		HttpUrl url = baseUrl.newBuilder().addQueryParameter("model", config.getModel()).build();
		Request.Builder request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + apiKey);
		if (sendBetaHeader)
		{
			request.header("OpenAI-Beta", "realtime=v1");
		}
		socket = http.newWebSocket(request.build(), new Listener());
	}

	@Override
	public void sendAudio(byte[] pcm16)
	{
		WebSocket ws = socket;
		if (ws == null || pcm16 == null || pcm16.length == 0)
		{
			return;
		}
		ws.send(gson.toJson(audioAppendEvent(Base64.getEncoder().encodeToString(pcm16))));
	}

	@Override
	public void commitUserAudio()
	{
		WebSocket ws = socket;
		if (ws == null)
		{
			return;
		}
		assistantText.setLength(0);
		ws.send(gson.toJson(event("input_audio_buffer.commit")));
		ws.send(gson.toJson(event("response.create")));
	}

	@Override
	public void close()
	{
		WebSocket ws = socket;
		socket = null;
		if (ws != null)
		{
			ws.close(1000, "client closed");
		}
		toolExecutor.shutdownNow();
	}

	// ── Event handling ─────────────────────────────────────────────────────────────────────────────

	private final class Listener extends WebSocketListener
	{
		@Override
		public void onOpen(WebSocket ws, Response response)
		{
			ws.send(gson.toJson(sessionUpdateEvent(config)));
			RealtimeSessionListener l = listener;
			if (l != null)
			{
				l.onConnected();
			}
		}

		@Override
		public void onMessage(WebSocket ws, String text)
		{
			try
			{
				handleEvent(ws, new JsonParser().parse(text).getAsJsonObject());
			}
			catch (RuntimeException e)
			{
				log.debug("Failed to handle realtime event", e);
			}
		}

		@Override
		public void onFailure(WebSocket ws, Throwable t, Response response)
		{
			RealtimeSessionListener l = listener;
			if (l != null)
			{
				l.onError(t.getMessage() == null ? "connection failed" : t.getMessage());
			}
		}

		@Override
		public void onClosed(WebSocket ws, int code, String reason)
		{
			RealtimeSessionListener l = listener;
			if (l != null)
			{
				l.onClosed();
			}
		}
	}

	private void handleEvent(WebSocket ws, JsonObject event)
	{
		RealtimeSessionListener l = listener;
		if (l == null)
		{
			return;
		}
		String type = Json.optString(event, "type", "");
		switch (type)
		{
			case "response.audio.delta":
			{
				String b64 = Json.optString(event, "delta", "");
				if (!b64.isEmpty())
				{
					l.onAssistantAudio(Base64.getDecoder().decode(b64));
				}
				break;
			}
			case "response.audio_transcript.delta":
				assistantText.append(Json.optString(event, "delta", ""));
				break;
			case "response.audio_transcript.done":
			{
				String full = Json.optString(event, "transcript", assistantText.toString());
				if (!full.isEmpty())
				{
					l.onAssistantText(full);
				}
				break;
			}
			case "conversation.item.input_audio_transcription.completed":
			{
				String transcript = Json.optString(event, "transcript", "");
				if (!transcript.isEmpty())
				{
					l.onUserTranscript(transcript);
				}
				break;
			}
			case "response.function_call_arguments.done":
				dispatchToolCall(ws, event);
				break;
			case "response.done":
				l.onResponseDone();
				break;
			case "error":
			{
				JsonObject err = Json.optObject(event, "error");
				l.onError(err == null ? "realtime error" : Json.optString(err, "message", "realtime error"));
				break;
			}
			default:
				// Ignore the many informational events we don't act on.
				break;
		}
	}

	private void dispatchToolCall(WebSocket ws, JsonObject event)
	{
		String callId = Json.optString(event, "call_id", "");
		String name = Json.optString(event, "name", "");
		JsonObject args = parseArguments(Json.optString(event, "arguments", ""));
		toolExecutor.submit(() ->
		{
			RealtimeSessionListener l = listener;
			if (l == null)
			{
				return;
			}
			String output;
			try
			{
				output = l.onToolCall(name, args);
			}
			catch (RuntimeException e)
			{
				output = "Tool error: " + e.getMessage();
			}
			ws.send(gson.toJson(functionResultItemEvent(callId, output)));
			ws.send(gson.toJson(event("response.create")));
		});
	}

	private JsonObject parseArguments(String arguments)
	{
		if (arguments == null || arguments.trim().isEmpty())
		{
			return new JsonObject();
		}
		try
		{
			JsonElement el = new JsonParser().parse(arguments);
			return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		}
		catch (RuntimeException e)
		{
			return new JsonObject();
		}
	}

	// ── Wire format (kept static and pure for testing) ──────────────────────────────────────────────

	static JsonObject event(String type)
	{
		JsonObject e = new JsonObject();
		e.addProperty("type", type);
		return e;
	}

	static JsonObject audioAppendEvent(String base64Pcm)
	{
		JsonObject e = event("input_audio_buffer.append");
		e.addProperty("audio", base64Pcm);
		return e;
	}

	static JsonObject functionResultItemEvent(String callId, String output)
	{
		JsonObject item = new JsonObject();
		item.addProperty("type", "function_call_output");
		item.addProperty("call_id", callId);
		item.addProperty("output", output == null ? "" : output);

		JsonObject e = event("conversation.item.create");
		e.add("item", item);
		return e;
	}

	static JsonObject sessionUpdateEvent(RealtimeSessionConfig config)
	{
		JsonObject session = new JsonObject();
		session.addProperty("instructions", config.getInstructions());

		JsonArray modalities = new JsonArray();
		modalities.add("audio");
		modalities.add("text");
		session.add("modalities", modalities);

		if (config.getVoice() != null && !config.getVoice().isEmpty())
		{
			session.addProperty("voice", config.getVoice());
		}
		session.addProperty("input_audio_format", "pcm16");
		session.addProperty("output_audio_format", "pcm16");

		JsonObject transcription = new JsonObject();
		transcription.addProperty("model", "whisper-1");
		session.add("input_audio_transcription", transcription);

		// Push-to-talk: we commit turns manually, so disable server voice-activity detection.
		session.add("turn_detection", com.google.gson.JsonNull.INSTANCE);

		if (config.getTools() != null && !config.getTools().isEmpty())
		{
			JsonArray tools = new JsonArray();
			for (ToolSpec spec : config.getTools())
			{
				JsonObject tool = new JsonObject();
				tool.addProperty("type", "function");
				tool.addProperty("name", spec.getName());
				tool.addProperty("description", spec.getDescription());
				tool.add("parameters", spec.getInputSchema());
				tools.add(tool);
			}
			session.add("tools", tools);
			session.addProperty("tool_choice", "auto");
		}

		JsonObject e = event("session.update");
		e.add("session", session);
		return e;
	}
}
