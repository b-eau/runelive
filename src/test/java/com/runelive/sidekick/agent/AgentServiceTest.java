package com.runelive.sidekick.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.llm.LlmMessage;
import com.runelive.sidekick.llm.LlmRequest;
import com.runelive.sidekick.llm.Modality;
import com.runelive.sidekick.llm.StopReason;
import com.runelive.sidekick.testutil.FakeLlmClient;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AgentServiceTest
{
	private static PlayerContext context()
	{
		return new PlayerContext("Zezima", "regular", "main", 100, 1500, 1_000_000L,
			10.0, 5.0, null, null, Map.of(), Map.of(), Map.of());
	}

	/** A tool that records its last input and returns a canned answer. */
	private static final class RecordingTool implements AgentTool
	{
		JsonObject lastInput;
		final String result;

		RecordingTool(String result)
		{
			this.result = result;
		}

		@Override
		public String name()
		{
			return "price_tool";
		}

		@Override
		public String description()
		{
			return "test tool";
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
			this.lastInput = input;
			return result;
		}
	}

	@Test
	public void runsToolLoopAndReturnsFinalAnswer()
	{
		RecordingTool tool = new RecordingTool("Abyssal whip: ~1,645,000 gp");
		ToolRegistry registry = new ToolRegistry(List.of(tool));

		JsonObject input = new JsonObject();
		input.addProperty("item", "Abyssal whip");
		FakeLlmClient llm = new FakeLlmClient()
			.script(FakeLlmClient.toolUse("toolu_1", "price_tool", input))
			.script(FakeLlmClient.endTurn("An Abyssal whip costs about 1.6m gp."));

		AgentService agent = new AgentService(llm, registry, 8);
		AgentReply reply = agent.chat(context(), Modality.TEXT, List.of(LlmMessage.userText("how much is a whip?")));

		assertEquals("An Abyssal whip costs about 1.6m gp.", reply.getText());
		assertEquals(StopReason.END_TURN, reply.getStopReason());

		// The tool actually received the model's input.
		assertEquals("Abyssal whip", tool.lastInput.get("item").getAsString());

		// The invocation is surfaced for the UI.
		assertEquals(1, reply.getToolInvocations().size());
		ToolInvocation invocation = reply.getToolInvocations().get(0);
		assertEquals("price_tool", invocation.getName());
		assertFalse(invocation.isError());
		assertTrue(invocation.getOutput().contains("1,645,000"));

		// Second model call must include the assistant tool_use replay + a tool_result user turn.
		assertEquals(2, llm.requests.size());
		LlmRequest second = llm.requests.get(1);
		assertEquals(3, second.getMessages().size());
		LlmMessage assistantTurn = second.getMessages().get(1);
		assertEquals("assistant", assistantTurn.getRole());
		LlmMessage toolResultTurn = second.getMessages().get(2);
		assertEquals("user", toolResultTurn.getRole());
		JsonArray resultBlocks = toolResultTurn.getContent().getAsJsonArray();
		assertEquals("tool_result", resultBlocks.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("toolu_1", resultBlocks.get(0).getAsJsonObject().get("tool_use_id").getAsString());
	}

	@Test
	public void marksUnknownToolAsError()
	{
		ToolRegistry registry = new ToolRegistry(List.of());
		FakeLlmClient llm = new FakeLlmClient()
			.script(FakeLlmClient.toolUse("toolu_1", "does_not_exist", new JsonObject()))
			.script(FakeLlmClient.endTurn("Recovered."));

		AgentService agent = new AgentService(llm, registry, 8);
		AgentReply reply = agent.chat(context(), Modality.TEXT, List.of(LlmMessage.userText("hi")));

		assertEquals("Recovered.", reply.getText());
		assertEquals(1, reply.getToolInvocations().size());
		assertTrue("missing tool surfaces as an error result", reply.getToolInvocations().get(0).isError());
	}

	@Test
	public void stopsAtMaxSteps()
	{
		RecordingTool tool = new RecordingTool("again");
		ToolRegistry registry = new ToolRegistry(List.of(tool));
		FakeLlmClient llm = new FakeLlmClient()
			.script(FakeLlmClient.toolUse("t1", "price_tool", new JsonObject()))
			.script(FakeLlmClient.toolUse("t2", "price_tool", new JsonObject()))
			.script(FakeLlmClient.toolUse("t3", "price_tool", new JsonObject()));

		AgentService agent = new AgentService(llm, registry, 2);
		AgentReply reply = agent.chat(context(), Modality.TEXT, List.of(LlmMessage.userText("hi")));

		assertEquals(StopReason.OTHER, reply.getStopReason());
		assertEquals("ran exactly maxSteps model calls", 2, llm.requests.size());
	}

	@Test
	public void textAndVoiceProduceDifferentSystemPrompts()
	{
		ToolRegistry registry = new ToolRegistry(List.of());
		FakeLlmClient textLlm = new FakeLlmClient().script(FakeLlmClient.endTurn("t"));
		new AgentService(textLlm, registry, 4).chat(context(), Modality.TEXT, List.of(LlmMessage.userText("hi")));

		FakeLlmClient voiceLlm = new FakeLlmClient().script(FakeLlmClient.endTurn("v"));
		new AgentService(voiceLlm, registry, 4).chat(context(), Modality.VOICE, List.of(LlmMessage.userText("hi")));

		String textSystem = textLlm.requests.get(0).getSystem();
		String voiceSystem = voiceLlm.requests.get(0).getSystem();
		assertTrue(textSystem.contains("TEXT CHAT"));
		assertTrue(voiceSystem.contains("VOICE CHAT"));
		assertFalse(textSystem.contains("READ ALOUD"));
		assertTrue(voiceSystem.contains("READ ALOUD"));
	}
}
