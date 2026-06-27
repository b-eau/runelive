package com.runelive.sidekick.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.runelive.sidekick.agent.AgentService;
import com.runelive.sidekick.agent.ToolRegistry;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerContextSource;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.llm.Role;
import com.runelive.sidekick.testutil.FakeLlmClient;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ChatServiceTest
{
	private static PlayerContext contextFor(String username)
	{
		return PlayerContext.builder()
			.username(username).accountType("ironman").build("main")
			.combatLevel(110).totalLevel(1750).totalExperience(5_000_000L)
			.efficientHoursPlayed(20.0).efficientHoursBossed(8.0)
			.skills(Map.of()).bosses(Map.of()).activities(Map.of())
			.build();
	}

	private ChatService service(FakeLlmClient llm, String defaultPlayer)
	{
		PlayerContextSource source = username ->
		{
			if ("ghost".equalsIgnoreCase(username))
			{
				throw new PlayerNotFoundException(username);
			}
			// Echo the requested name back (capitalised) like a real source's display name.
			String display = Character.toUpperCase(username.charAt(0)) + username.substring(1);
			return contextFor(display);
		};
		AgentService agent = new AgentService(llm, new ToolRegistry(List.of()), 4);
		return new ChatService(source, agent, defaultPlayer);
	}

	private static ChatDtos.ChatRequest request(String player, String modality, ChatDtos.Turn... turns)
	{
		ChatDtos.ChatRequest req = new ChatDtos.ChatRequest();
		req.player = player;
		req.modality = modality;
		req.messages = List.of(turns);
		return req;
	}

	@Test
	public void answersAndSummarisesContext()
	{
		FakeLlmClient llm = new FakeLlmClient().script(FakeLlmClient.endTurn("Go do Barrows."));
		ChatService service = service(llm, null);

		ChatDtos.ChatResponse response = service.handle(
			request("zezima", "text", new ChatDtos.Turn("user", "what should I do?")));

		assertNull(response.error);
		assertEquals("Go do Barrows.", response.reply);
		assertEquals("text", response.modality);
		assertNotNull(response.context);
		assertEquals("Zezima", response.context.username);
		assertEquals(110, response.context.combatLevel);
		assertTrue(response.context.ironman);
	}

	@Test
	public void fallsBackToDefaultPlayer()
	{
		FakeLlmClient llm = new FakeLlmClient().script(FakeLlmClient.endTurn("ok"));
		ChatService service = service(llm, "DefaultGuy");

		ChatDtos.ChatResponse response = service.handle(
			request(null, "text", new ChatDtos.Turn("user", "hi")));

		assertNull(response.error);
		assertEquals("DefaultGuy", response.context.username);
	}

	@Test
	public void errorsWhenNoPlayer()
	{
		ChatService service = service(new FakeLlmClient(), null);
		ChatDtos.ChatResponse response = service.handle(
			request("", "text", new ChatDtos.Turn("user", "hi")));
		assertNotNull(response.error);
		assertTrue(response.error.toLowerCase().contains("username"));
	}

	@Test
	public void errorsWhenNoMessages()
	{
		ChatService service = service(new FakeLlmClient(), null);
		ChatDtos.ChatResponse response = service.handle(request("zezima", "text"));
		assertNotNull(response.error);
	}

	@Test
	public void errorsWhenLastTurnIsNotUser()
	{
		ChatService service = service(new FakeLlmClient(), null);
		ChatDtos.ChatResponse response = service.handle(
			request("zezima", "text",
				new ChatDtos.Turn("user", "hi"),
				new ChatDtos.Turn("assistant", "hello")));
		assertNotNull(response.error);
		assertTrue(response.error.toLowerCase().contains("user message"));
	}

	@Test
	public void passesPriorTurnsAsHistory()
	{
		FakeLlmClient llm = new FakeLlmClient().script(FakeLlmClient.endTurn("answer"));
		ChatService service = service(llm, null);

		service.handle(request("zezima", "voice",
			new ChatDtos.Turn("user", "first"),
			new ChatDtos.Turn("assistant", "earlier reply"),
			new ChatDtos.Turn("user", "second")));

		assertEquals(3, llm.requests.get(0).getMessages().size());
		assertEquals(Role.ASSISTANT, llm.requests.get(0).getMessages().get(1).getRole());
	}
}
