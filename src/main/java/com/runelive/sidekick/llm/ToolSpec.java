package com.runelive.sidekick.llm;

import com.google.gson.JsonObject;
import lombok.Value;

/** A tool the model may call: a name, a description, and a JSON-schema for its input. */
@Value
public class ToolSpec
{
	String name;
	String description;
	JsonObject inputSchema;
}
