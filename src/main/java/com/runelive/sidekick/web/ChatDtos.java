package com.runelive.sidekick.web;

import java.util.List;

/** Wire DTOs for the {@code /api/chat} endpoint (serialized by Gson). */
public final class ChatDtos
{
	private ChatDtos()
	{
	}

	/** Inbound request: the full transcript, the modality, and (optionally) the player RSN. */
	public static final class ChatRequest
	{
		public List<Turn> messages;
		public String modality;
		public String player;
	}

	public static final class Turn
	{
		public String role;    // "user" | "assistant"
		public String content;

		public Turn()
		{
		}

		public Turn(String role, String content)
		{
			this.role = role;
			this.content = content;
		}
	}

	/** Outbound response. {@code error} is populated (and the rest null) on failure. */
	public static final class ChatResponse
	{
		public String reply;
		public String modality;
		public String player;
		public ContextSummary context;
		public List<ToolCallDto> tools;
		public Usage usage;
		public String error;

		public static ChatResponse error(String message)
		{
			ChatResponse r = new ChatResponse();
			r.error = message;
			return r;
		}
	}

	/** Small account summary so the UI can show who the advice is for. */
	public static final class ContextSummary
	{
		public String username;
		public String accountType;
		public int combatLevel;
		public int totalLevel;
		public boolean ironman;
	}

	public static final class ToolCallDto
	{
		public String name;
		public String input;
		public String output;
		public boolean error;
	}

	public static final class Usage
	{
		public int inputTokens;
		public int outputTokens;
	}
}
