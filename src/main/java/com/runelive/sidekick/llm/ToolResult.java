package com.runelive.sidekick.llm;

import lombok.Value;

/** The result of executing a {@link ToolCall}, sent back to the model. */
@Value
public class ToolResult
{
	String toolUseId;
	String name;
	String content;
	boolean error;
}
