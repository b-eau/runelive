package com.runelive.sidekick.llm;

import com.google.gson.JsonElement;
import java.util.List;
import lombok.Value;

/** The model's response to an {@link LlmRequest}. */
@Value
public class LlmResult
{
	StopReason stopReason;
	/** Concatenated text blocks (may be empty when the model only requested tools or refused). */
	String text;
	/** Tool calls the model wants executed before continuing. */
	List<ToolCall> toolCalls;
	/** The raw assistant content block array, replayed verbatim on the next request. */
	JsonElement assistantContent;
	int inputTokens;
	int outputTokens;

	public boolean wantsTools()
	{
		return stopReason == StopReason.TOOL_USE && !toolCalls.isEmpty();
	}
}
