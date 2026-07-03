package com.runelive.sidekick.llm;

import com.google.gson.JsonObject;
import lombok.Value;

/**
 * A tool call emitted by the model (assistant turn).
 *
 * <p>{@code signature} is an optional, opaque provider token that must be replayed verbatim on the
 * next request — e.g. a Gemini 3 {@code thoughtSignature}. Providers that don't use it leave it null.
 */
@Value
public class ToolUsePart implements ContentPart
{
	String id;
	String name;
	JsonObject input;
	String signature;
}
