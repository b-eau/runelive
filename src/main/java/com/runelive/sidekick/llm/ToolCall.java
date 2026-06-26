package com.runelive.sidekick.llm;

import com.google.gson.JsonObject;
import lombok.Value;

/** A tool invocation requested by the model. */
@Value
public class ToolCall
{
	String id;      // provider tool-call id, echoed back in the tool result
	String name;
	JsonObject input;
	String signature; // optional opaque provider token to replay (e.g. Gemini thoughtSignature); may be null
}
