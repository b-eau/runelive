package com.runelive.sidekick.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import lombok.Value;

/**
 * One turn in the conversation, in Anthropic Messages API shape.
 *
 * <p>{@code content} is either a JSON string (plain user text) or a JSON array of content blocks.
 * Assistant turns are stored as the raw block array returned by the model so that thinking and
 * tool_use blocks are replayed back verbatim on the next request — a requirement for multi-step
 * tool use (and for thinking blocks on the same model).
 */
@Value
public class LlmMessage
{
	String role; // "user" | "assistant"
	JsonElement content;

	public static LlmMessage userText(String text)
	{
		return new LlmMessage("user", new JsonPrimitive(text));
	}

	public static LlmMessage assistant(JsonElement contentBlocks)
	{
		return new LlmMessage("assistant", contentBlocks);
	}

	/** A plain-text assistant turn (e.g. a prior reply replayed from the client transcript). */
	public static LlmMessage assistantText(String text)
	{
		return new LlmMessage("assistant", new JsonPrimitive(text));
	}

	/** A user turn carrying one tool_result block per executed tool call. */
	public static LlmMessage toolResults(List<ToolResult> results)
	{
		JsonArray blocks = new JsonArray();
		for (ToolResult result : results)
		{
			JsonObject block = new JsonObject();
			block.addProperty("type", "tool_result");
			block.addProperty("tool_use_id", result.getToolUseId());
			block.addProperty("content", result.getContent());
			if (result.isError())
			{
				block.addProperty("is_error", true);
			}
			blocks.add(block);
		}
		return new LlmMessage("user", blocks);
	}
}
