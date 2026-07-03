package com.runelive.sidekick.conversation;

import com.runelive.sidekick.llm.LlmMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owns the <em>active</em> conversation thread and bridges persisted history to the agent:
 * it records turns, builds the provider-neutral message history for a turn, and produces the
 * memory block of recent-conversation summaries embedded in the system prompt.
 *
 * <p>Threads are scoped per account. Calls come from the plugin's single query-executor thread and
 * occasionally from the EDT (history navigation), so mutating methods are {@code synchronized}.
 */
public class ConversationManager
{
	/** Most recent past conversations summarised into the system prompt. */
	private static final int MEMORY_LIMIT = 6;

	private final ConversationStore store;
	private Conversation active;

	public ConversationManager(ConversationStore store)
	{
		this.store = store;
	}

	public synchronized Conversation current()
	{
		return active;
	}

	/** Discards the active thread so the next turn starts a fresh conversation. */
	public synchronized void startNew()
	{
		active = null;
	}

	/** Makes a previously-stored conversation the active thread (history navigation / "continue"). */
	public synchronized void setActive(Conversation conversation)
	{
		active = conversation;
	}

	/** Records the player's message, starting a new thread for this account when needed. */
	public synchronized void recordUser(String username, String text)
	{
		if (active == null || !sameUser(active.getUsername(), username))
		{
			active = new Conversation(UUID.randomUUID().toString().substring(0, 8), username,
				System.currentTimeMillis());
		}
		active.addTurn("user", text, System.currentTimeMillis());
		store.save(active);
	}

	/** Records the assistant's reply on the active thread and persists it. */
	public synchronized void recordAssistant(String text)
	{
		if (active == null)
		{
			return;
		}
		active.addTurn("assistant", text, System.currentTimeMillis());
		store.save(active);
	}

	/** The active thread as user/assistant text turns, ending with the latest user message. */
	public synchronized List<LlmMessage> history()
	{
		List<LlmMessage> history = new ArrayList<>();
		if (active == null)
		{
			return history;
		}
		for (Conversation.Turn turn : active.getTurns())
		{
			if (turn.getText() == null || turn.getText().isEmpty())
			{
				continue;
			}
			history.add(turn.isUser()
				? LlmMessage.userText(turn.getText())
				: LlmMessage.assistantText(turn.getText()));
		}
		return history;
	}

	/**
	 * A short block summarising the player's other recent conversations, for the system prompt.
	 * Returns {@code null} when there is nothing worth recalling.
	 */
	public synchronized String memoryBlock(String username)
	{
		String activeId = active == null ? null : active.getId();
		List<Conversation> past = store.list(username);
		StringBuilder sb = new StringBuilder();
		long now = System.currentTimeMillis();
		int count = 0;
		for (Conversation c : past)
		{
			if (activeId != null && activeId.equals(c.getId()))
			{
				continue;
			}
			if (count == 0)
			{
				sb.append("RECENT CONVERSATIONS WITH THIS PLAYER (earlier sessions — call "
					+ "recall_past_conversations with a conversation_id to read one in full):\n");
			}
			sb.append("- [").append(c.getId()).append("] \"").append(c.getTitle()).append("\" — ")
				.append(relativeTime(c.getUpdatedAt(), now)).append('\n');
			if (++count >= MEMORY_LIMIT)
			{
				break;
			}
		}
		return count == 0 ? null : sb.toString();
	}

	/** Recent conversations for the current account, for the history navigator UI. */
	public List<Conversation> recent(String username)
	{
		return store.list(username);
	}

	/** Past conversations other than the active one — used by the recall tool. */
	public synchronized List<Conversation> others(String username)
	{
		String activeId = active == null ? null : active.getId();
		List<Conversation> past = store.list(username);
		past.removeIf(c -> activeId != null && activeId.equals(c.getId()));
		return past;
	}

	public Conversation load(String username, String id)
	{
		return store.load(username, id);
	}

	private static boolean sameUser(String a, String b)
	{
		return a == null ? b == null : a.equalsIgnoreCase(b);
	}

	/** Human-friendly "x ago" rendering shared by the prompt memory block and the history list. */
	public static String relativeTime(long then, long now)
	{
		long delta = Math.max(0, now - then);
		long minutes = delta / 60_000L;
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + (hours == 1 ? " hour ago" : " hours ago");
		}
		long days = hours / 24;
		if (days < 30)
		{
			return days + (days == 1 ? " day ago" : " days ago");
		}
		long months = days / 30;
		return months + (months == 1 ? " month ago" : " months ago");
	}
}
