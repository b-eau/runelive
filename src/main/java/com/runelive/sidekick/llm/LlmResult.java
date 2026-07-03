package com.runelive.sidekick.llm;

import java.util.List;
import lombok.Value;

/** The model's response to an {@link LlmRequest}, in provider-neutral form. */
@Value
public class LlmResult
{
	StopReason stopReason;
	/** Concatenated text the model produced (may be empty when it only requested tools or refused). */
	String text;
	/** Tool calls the model wants executed before continuing. */
	List<ToolCall> toolCalls;
	int inputTokens;
	int outputTokens;

	public boolean wantsTools()
	{
		return stopReason == StopReason.TOOL_USE && !toolCalls.isEmpty();
	}
}
