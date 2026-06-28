package com.runelive.sidekick.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * A persisted chat thread between the player and the sidekick. Serialised to JSON (one file per
 * conversation) by {@link ConversationStore}. Mutation goes through {@link ConversationManager},
 * which owns the active thread; this class is a plain data holder.
 */
public class Conversation
{
	private String id;
	private String username;
	private String title;
	private long createdAt;
	private long updatedAt;
	private List<Turn> turns = new ArrayList<>();

	/** Gson needs a no-arg constructor path; this also seeds a fresh thread. */
	public Conversation()
	{
	}

	public Conversation(String id, String username, long createdAt)
	{
		this.id = id;
		this.username = username;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	/** Appends a turn, deriving the title from the first user message and bumping {@code updatedAt}. */
	public void addTurn(String role, String text, long at)
	{
		if (turns == null)
		{
			turns = new ArrayList<>();
		}
		turns.add(new Turn(role, text, at));
		updatedAt = at;
		if ((title == null || title.isEmpty()) && "user".equals(role) && text != null)
		{
			title = deriveTitle(text);
		}
	}

	private static String deriveTitle(String text)
	{
		String trimmed = text.trim().replaceAll("\\s+", " ");
		return trimmed.length() > 80 ? trimmed.substring(0, 79) + "…" : trimmed;
	}

	public String getId()
	{
		return id;
	}

	public String getUsername()
	{
		return username;
	}

	public String getTitle()
	{
		return title == null || title.isEmpty() ? "New conversation" : title;
	}

	public long getCreatedAt()
	{
		return createdAt;
	}

	public long getUpdatedAt()
	{
		return updatedAt;
	}

	public List<Turn> getTurns()
	{
		return turns == null ? new ArrayList<>() : turns;
	}

	public boolean isEmpty()
	{
		return turns == null || turns.isEmpty();
	}

	/** One message in a conversation. {@code role} is {@code "user"} or {@code "assistant"}. */
	public static final class Turn
	{
		private String role;
		private String text;
		private long at;

		public Turn()
		{
		}

		public Turn(String role, String text, long at)
		{
			this.role = role;
			this.text = text;
			this.at = at;
		}

		public String getRole()
		{
			return role;
		}

		public String getText()
		{
			return text;
		}

		public long getAt()
		{
			return at;
		}

		public boolean isUser()
		{
			return "user".equals(role);
		}
	}
}
