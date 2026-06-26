package com.runelive.sidekick.llm;

import com.google.gson.JsonObject;
import lombok.Value;

/** A tool invocation requested by the model. */
@Value
public class ToolCall
{
	String id;      // tool_use id, echoed back in the tool_result
	String name;
	JsonObject input;
}
