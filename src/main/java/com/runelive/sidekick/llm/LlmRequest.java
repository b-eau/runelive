package com.runelive.sidekick.llm;

import java.util.List;
import lombok.Value;

/** A single model request: a system prompt, the running message list, and the available tools. */
@Value
public class LlmRequest
{
	String system;
	List<LlmMessage> messages;
	List<ToolSpec> tools;
}
