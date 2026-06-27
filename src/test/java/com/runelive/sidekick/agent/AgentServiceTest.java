package com.runelive.sidekick.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.llm.LlmMessage;
import com.runelive.sidekick.llm.LlmRequest;
import com.runelive.sidekick.llm.Modality;
import com.runelive.sidekick.llm.Role;
import com.runelive.sidekick.llm.StopReason;
import com.runelive.sidekick.llm.ToolResultPart;
import com.runelive.sidekick.llm.ToolUsePart;
import com.runelive.sidekick.testutil.FakeLlmClient;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AgentServiceTest
{
	private static PlayerContext context()
	{
		return PlayerContext.builder()
			.username("Zezima").accountType("regular").build("main")
			.combatLevel(100).totalLevel(1500).totalExperience(1_000_000L)
			.efficientHoursPlayed(10.0).efficientHoursBossed(5.0)
			.skills(Map.of()).bosses(Map.of()).activities(Map.of())
			.build();
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
		AgentReply reply = agent.chat(context(), Modality.TEXT, List.of(LlmMessage.userText("how much is a whip?")), null);

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

		// Second model call must include the assistant tool-call replay + a tool-result user turn.
		assertEquals(2, llm.requests.size());
		LlmRequest second = llm.requests.get(1);
		assertEquals(3, second.getMessages().size());

		LlmMessage assistantTurn = second.getMessages().get(1);
		assertEquals(Role.ASSISTANT, assistantTurn.getRole());
		assertTrue(assistantTurn.getParts().get(0) instanceof ToolUsePart);
		assertEquals("price_tool", ((ToolUsePart) assistantTurn.getParts().get(0)).getName());

		LlmMessage toolResultTurn = second.getMessages().get(2);
		assertEquals(Role.USER, toolResultTurn.getRole());
		assertTrue(toolResultTurn.getParts().get(0) instanceof ToolResultPart);
		ToolResultPart resultPart = (ToolResultPart) toolResultTurn.getParts().get(0);
		assertEquals("toolu_1", resultPart.getToolUseId());
		assertEquals("price_tool", resultPart.getName());
	}

	@Test
	public void marksUnknownToolAsError()
	{
		ToolRegistry registry = new ToolRegistry(List.of());
		FakeLlmClient llm = new FakeLlmClient()
			.script(FakeLlmClient.toolUse("toolu_1", "does_not_exist", new JsonObject()))
			.script(FakeLlmClient.endTurn("Recovered."));

		AgentService agent = new AgentService(llm, registry, 8);
		AgentReply reply = agent.chat(context(), Modality.TEXT, List.of(LlmMessage.userText("hi")), null);

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
			.script(FakeLlmClient.endTurn("Here is what I found."));

		AgentService agent = new AgentService(llm, registry, 2);
		AgentReply reply = agent.chat(context(), Modality.TEXT, List.of(LlmMessage.userText("hi")), null);

		assertEquals(StopReason.END_TURN, reply.getStopReason());
		assertEquals("graceful: maxSteps tool calls then one final no-tool request", 3, llm.requests.size());
		assertTrue("final request must have no tools", llm.requests.get(2).getTools().isEmpty());
		assertEquals("Here is what I found.", reply.getText());
	}

	@Test
	public void textAndVoiceProduceDifferentSystemPrompts()
	{
		ToolRegistry registry = new ToolRegistry(List.of());
		FakeLlmClient textLlm = new FakeLlmClient().script(FakeLlmClient.endTurn("t"));
		new AgentService(textLlm, registry, 4).chat(context(), Modality.TEXT, List.of(LlmMessage.userText("hi")), null);

		FakeLlmClient voiceLlm = new FakeLlmClient().script(FakeLlmClient.endTurn("v"));
		new AgentService(voiceLlm, registry, 4).chat(context(), Modality.VOICE, List.of(LlmMessage.userText("hi")), null);

		String textSystem = textLlm.requests.get(0).getSystem();
		String voiceSystem = voiceLlm.requests.get(0).getSystem();
		assertTrue(textSystem.contains("TEXT CHAT"));
		assertTrue(voiceSystem.contains("VOICE CHAT"));
		assertFalse(textSystem.contains("READ ALOUD"));
		assertTrue(voiceSystem.contains("READ ALOUD"));
	}
}
