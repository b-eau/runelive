package com.runelive.sidekick.llm;

import lombok.Value;

/** The result of a tool call, sent back to the model (user turn). Carries both the tool-use id
 * (used by Anthropic) and the tool name (used by Gemini) so either provider can correlate it. */
@Value
public class ToolResultPart implements ContentPart
{
	String toolUseId;
	String name;
	String content;
	boolean error;
}
