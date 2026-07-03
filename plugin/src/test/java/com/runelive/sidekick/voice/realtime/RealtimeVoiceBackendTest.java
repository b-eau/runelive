package com.runelive.sidekick.voice.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.runelive.sidekick.agent.ToolRegistry;
import com.runelive.sidekick.agent.tools.AgentTool;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RealtimeVoiceBackendTest
{
	/** A tool that echoes a fixed result and records the input it received. */
	private static final class EchoTool implements AgentTool
	{
		JsonObject lastInput;

		@Override
		public String name()
		{
			return "get_grand_exchange_price";
		}

		@Override
		public String description()
		{
			return "test";
		}

		@Override
		public JsonObject inputSchema()
		{
			JsonObject s = new JsonObject();
			s.addProperty("type", "object");
			return s;
		}

		@Override
		public String execute(JsonObject input)
		{
			lastInput = input;
			return "1,645,000 gp";
		}
	}

	private static RealtimeVoiceBackend backend(ToolRegistry registry, List<String> steps)
	{
		return new RealtimeVoiceBackend(
			null, registry, null, null, null,
			() -> { }, steps::add, s -> { }, "grok-realtime", null);
	}

	@Test
	public void executesKnownToolAndReportsStep()
	{
		EchoTool tool = new EchoTool();
		List<String> steps = new ArrayList<>();
		RealtimeVoiceBackend backend = backend(new ToolRegistry(List.of(tool)), steps);

		JsonObject args = new JsonObject();
		args.addProperty("item", "Abyssal whip");
		String result = backend.onToolCall("get_grand_exchange_price", args);

		assertEquals("1,645,000 gp", result);
		assertEquals("Abyssal whip", tool.lastInput.get("item").getAsString());
		assertTrue("the tool step is surfaced for parity", steps.contains("get grand exchange price"));
	}

	@Test
	public void unknownToolIsReportedNotThrown()
	{
		RealtimeVoiceBackend backend = backend(new ToolRegistry(List.of()), new ArrayList<>());
		assertTrue(backend.onToolCall("does_not_exist", new JsonObject()).contains("Unknown tool"));
	}
}
