package com.runelive.sidekick.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.runelive.sidekick.llm.LlmMessage;
import com.runelive.sidekick.llm.Role;
import com.runelive.sidekick.llm.TextPart;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ConversationManagerTest
{
	/** In-memory stand-in for {@link ConversationStore} that round-trips through JSON like disk. */
	private static final class InMemoryStore extends ConversationStore
	{
		private final Gson gson = new Gson();
		private final Map<String, Map<String, String>> data = new LinkedHashMap<>();

		InMemoryStore()
		{
			super(new Gson());
		}

		@Override
		public void save(Conversation conversation)
		{
			if (conversation == null || conversation.isEmpty()
				|| conversation.getId() == null || conversation.getUsername() == null)
			{
				return;
			}
			data.computeIfAbsent(key(conversation.getUsername()), k -> new LinkedHashMap<>())
				.put(conversation.getId(), gson.toJson(conversation));
		}

		@Override
		public List<Conversation> list(String username)
		{
			Map<String, String> byId = data.get(key(username));
			java.util.List<Conversation> out = new java.util.ArrayList<>();
			if (byId != null)
			{
				for (String json : byId.values())
				{
					out.add(gson.fromJson(json, Conversation.class));
				}
			}
			out.sort(Comparator.comparingLong(Conversation::getUpdatedAt).reversed());
			return out;
		}

		@Override
		public Conversation load(String username, String id)
		{
			Map<String, String> byId = data.get(key(username));
			if (byId == null || !byId.containsKey(id))
			{
				return null;
			}
			return gson.fromJson(byId.get(id), Conversation.class);
		}

		private static String key(String username)
		{
			return username == null ? "" : username.toLowerCase();
		}
	}

	private static String textOf(LlmMessage message)
	{
		return ((TextPart) message.getParts().get(0)).getText();
	}

	@Test
	public void recordsTurnsAndBuildsHistoryInOrder()
	{
		ConversationManager manager = new ConversationManager(new InMemoryStore());

		manager.recordUser("Zezima", "How do I start Zulrah?");
		manager.recordAssistant("Finish Regicide first.");
		manager.recordUser("Zezima", "What gear?");

		List<LlmMessage> history = manager.history();
		assertEquals(3, history.size());
		assertEquals(Role.USER, history.get(0).getRole());
		assertEquals("How do I start Zulrah?", textOf(history.get(0)));
		assertEquals(Role.ASSISTANT, history.get(1).getRole());
		assertEquals("Finish Regicide first.", textOf(history.get(1)));
		assertEquals(Role.USER, history.get(2).getRole());
		assertEquals("What gear?", textOf(history.get(2)));

		// Title is derived from the first user message.
		assertEquals("How do I start Zulrah?", manager.current().getTitle());
	}

	@Test
	public void switchingAccountStartsAFreshThread()
	{
		ConversationManager manager = new ConversationManager(new InMemoryStore());
		manager.recordUser("Zezima", "first");
		String firstId = manager.current().getId();

		manager.recordUser("Woox", "different account");
		assertFalse("a different account gets a new thread", firstId.equals(manager.current().getId()));
		assertEquals(1, manager.history().size());
	}

	@Test
	public void startNewBeginsAnEmptyThread()
	{
		ConversationManager manager = new ConversationManager(new InMemoryStore());
		manager.recordUser("Zezima", "hello");
		manager.recordAssistant("hi");

		manager.startNew();
		assertNull(manager.current());
		assertTrue(manager.history().isEmpty());
	}

	@Test
	public void memoryBlockListsPastConversationsExcludingActive()
	{
		InMemoryStore store = new InMemoryStore();
		ConversationManager manager = new ConversationManager(store);

		// An older, completed conversation.
		manager.recordUser("Zezima", "Best money maker at 70 range?");
		manager.recordAssistant("Vorkath if you can.");
		String oldId = manager.current().getId();

		// Start a new active thread.
		manager.startNew();
		manager.recordUser("Zezima", "How about now?");

		String memory = manager.memoryBlock("Zezima");
		assertTrue(memory.contains("RECENT CONVERSATIONS WITH THIS PLAYER"));
		assertTrue("past thread is listed by id", memory.contains("[" + oldId + "]"));
		assertTrue("past thread title is listed", memory.contains("Best money maker at 70 range?"));
		assertFalse("the active thread is not listed", memory.contains("How about now?"));
	}

	@Test
	public void memoryBlockIsNullWhenNoPastConversations()
	{
		ConversationManager manager = new ConversationManager(new InMemoryStore());
		manager.recordUser("Zezima", "only conversation");
		assertNull(manager.memoryBlock("Zezima"));
	}

	@Test
	public void relativeTimeBuckets()
	{
		long now = 1_000_000_000L;
		assertEquals("just now", ConversationManager.relativeTime(now - 30_000L, now));
		assertEquals("1 minute ago", ConversationManager.relativeTime(now - 60_000L, now));
		assertEquals("5 minutes ago", ConversationManager.relativeTime(now - 5 * 60_000L, now));
		assertEquals("1 hour ago", ConversationManager.relativeTime(now - 3_600_000L, now));
		assertEquals("2 days ago", ConversationManager.relativeTime(now - 2 * 86_400_000L, now));
	}
}
