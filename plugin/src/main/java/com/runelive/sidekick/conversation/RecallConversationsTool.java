package com.runelive.sidekick.conversation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runelive.sidekick.agent.tools.AgentTool;
import java.util.List;

/**
 * Tool: lets the agent recall the player's earlier conversations so its advice stays consistent
 * across sessions. With no arguments it lists recent past threads; with a {@code conversation_id}
 * it returns that thread's full transcript; with a {@code query} it finds matching threads.
 *
 * <p>Reads are scoped to the currently-active account via {@link ConversationManager}.
 */
public class RecallConversationsTool implements AgentTool
{
	private static final int LIST_LIMIT = 10;
	private static final int MAX_TURN_CHARS = 600;

	private final ConversationManager manager;

	public RecallConversationsTool(ConversationManager manager)
	{
		this.manager = manager;
	}

	@Override
	public String name()
	{
		return "recall_past_conversations";
	}

	@Override
	public String description()
	{
		return "Recall the player's earlier conversations with you so your advice stays consistent "
			+ "across sessions. Call with no arguments to list recent past conversations (id, title, "
			+ "when). Pass a conversation_id to read that conversation's full transcript. Pass a query "
			+ "to find past conversations whose text matches it.";
	}

	@Override
	public JsonObject inputSchema()
	{
		JsonObject conversationId = new JsonObject();
		conversationId.addProperty("type", "string");
		conversationId.addProperty("description", "Id of a past conversation to read in full (from the list).");

		JsonObject query = new JsonObject();
		query.addProperty("type", "string");
		query.addProperty("description", "Text to search for across past conversations.");

		JsonObject properties = new JsonObject();
		properties.add("conversation_id", conversationId);
		properties.add("query", query);

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", new JsonArray());
		schema.addProperty("additionalProperties", false);
		return schema;
	}

	@Override
	public String execute(JsonObject input)
	{
		Conversation activeThread = manager.current();
		String username = activeThread == null ? null : activeThread.getUsername();
		if (username == null)
		{
			return "No conversation history is available yet.";
		}

		String conversationId = optString(input, "conversation_id");
		if (!conversationId.isEmpty())
		{
			Conversation conversation = manager.load(username, conversationId);
			if (conversation == null || conversation.isEmpty())
			{
				return "No past conversation found with id \"" + conversationId + "\".";
			}
			return transcript(conversation);
		}

		List<Conversation> past = manager.others(username);
		String query = optString(input, "query").toLowerCase();
		if (!query.isEmpty())
		{
			past.removeIf(c -> !matches(c, query));
		}
		if (past.isEmpty())
		{
			return query.isEmpty()
				? "No past conversations yet."
				: "No past conversations matched \"" + query + "\".";
		}
		return list(past);
	}

	private static boolean matches(Conversation conversation, String lowerQuery)
	{
		if (conversation.getTitle().toLowerCase().contains(lowerQuery))
		{
			return true;
		}
		for (Conversation.Turn turn : conversation.getTurns())
		{
			if (turn.getText() != null && turn.getText().toLowerCase().contains(lowerQuery))
			{
				return true;
			}
		}
		return false;
	}

	private static String list(List<Conversation> conversations)
	{
		long now = System.currentTimeMillis();
		StringBuilder sb = new StringBuilder("Past conversations (most recent first):\n");
		int shown = 0;
		for (Conversation c : conversations)
		{
			sb.append("- [").append(c.getId()).append("] \"").append(c.getTitle()).append("\" — ")
				.append(ConversationManager.relativeTime(c.getUpdatedAt(), now))
				.append(" (").append(c.getTurns().size()).append(" messages)\n");
			if (++shown >= LIST_LIMIT)
			{
				break;
			}
		}
		return sb.toString();
	}

	private static String transcript(Conversation conversation)
	{
		StringBuilder sb = new StringBuilder("Conversation \"").append(conversation.getTitle())
			.append("\" (").append(ConversationManager.relativeTime(
				conversation.getUpdatedAt(), System.currentTimeMillis())).append("):\n");
		for (Conversation.Turn turn : conversation.getTurns())
		{
			String text = turn.getText() == null ? "" : turn.getText();
			if (text.length() > MAX_TURN_CHARS)
			{
				text = text.substring(0, MAX_TURN_CHARS) + "…";
			}
			sb.append(turn.isUser() ? "Player: " : "You (Sidekick): ").append(text).append('\n');
		}
		return sb.toString();
	}

	private static String optString(JsonObject input, String key)
	{
		if (input == null || !input.has(key) || input.get(key).isJsonNull())
		{
			return "";
		}
		return input.get(key).getAsString().trim();
	}
}
