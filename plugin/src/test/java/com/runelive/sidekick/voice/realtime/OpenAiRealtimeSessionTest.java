package com.runelive.sidekick.voice.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.runelive.sidekick.llm.ToolSpec;
import java.util.List;
import org.junit.Test;

public class OpenAiRealtimeSessionTest
{
	private static RealtimeSessionConfig config()
	{
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		return RealtimeSessionConfig.builder()
			.model("grok-voice-latest")
			.instructions("You are Sidekick.")
			.voice("eve")
			.tools(List.of(new ToolSpec("get_grand_exchange_price", "Price lookup", schema)))
			.inputSampleRateHz(24_000)
			.outputSampleRateHz(24_000)
			.build();
	}

	@Test
	public void sessionUpdateCarriesInstructionsToolsAndAudioFormat()
	{
		JsonObject e = OpenAiRealtimeSession.sessionUpdateEvent(config());
		assertEquals("session.update", e.get("type").getAsString());

		JsonObject session = e.getAsJsonObject("session");
		assertEquals("You are Sidekick.", session.get("instructions").getAsString());
		assertEquals("eve", session.get("voice").getAsString());
		assertEquals("pcm16", session.get("input_audio_format").getAsString());
		assertEquals("pcm16", session.get("output_audio_format").getAsString());
		// Push-to-talk → manual turn commit, so server VAD is disabled.
		assertTrue(session.get("turn_detection").isJsonNull());
		assertEquals("auto", session.get("tool_choice").getAsString());

		JsonObject tool = session.getAsJsonArray("tools").get(0).getAsJsonObject();
		assertEquals("function", tool.get("type").getAsString());
		assertEquals("get_grand_exchange_price", tool.get("name").getAsString());
		assertTrue(tool.has("parameters"));
	}

	@Test
	public void audioAppendEncodesBase64()
	{
		JsonObject e = OpenAiRealtimeSession.audioAppendEvent("AAAB");
		assertEquals("input_audio_buffer.append", e.get("type").getAsString());
		assertEquals("AAAB", e.get("audio").getAsString());
	}

	@Test
	public void functionResultIsAConversationItem()
	{
		JsonObject e = OpenAiRealtimeSession.functionResultItemEvent("call_1", "1,645,000 gp");
		assertEquals("conversation.item.create", e.get("type").getAsString());
		JsonObject item = e.getAsJsonObject("item");
		assertEquals("function_call_output", item.get("type").getAsString());
		assertEquals("call_1", item.get("call_id").getAsString());
		assertEquals("1,645,000 gp", item.get("output").getAsString());
	}

	@Test
	public void plainEventCarriesOnlyType()
	{
		assertEquals("response.create", OpenAiRealtimeSession.event("response.create").get("type").getAsString());
	}
}
