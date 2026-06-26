package com.runelive.sidekick.llm;

import java.util.ArrayList;
import java.util.List;
import lombok.Value;

/**
 * One turn in the conversation, as provider-neutral {@link ContentPart}s. Each {@link LlmClient}
 * translates these to its own wire format.
 */
@Value
public class LlmMessage
{
	Role role;
	List<ContentPart> parts;

	public static LlmMessage userText(String text)
	{
		return new LlmMessage(Role.USER, List.of(new TextPart(text)));
	}

	public static LlmMessage assistantText(String text)
	{
		return new LlmMessage(Role.ASSISTANT, List.of(new TextPart(text)));
	}

	public static LlmMessage assistant(List<ContentPart> parts)
	{
		return new LlmMessage(Role.ASSISTANT, parts);
	}

	/** A user turn carrying one tool-result part per executed tool call. */
	public static LlmMessage toolResults(List<ToolResult> results)
	{
		List<ContentPart> parts = new ArrayList<>();
		for (ToolResult result : results)
		{
			parts.add(new ToolResultPart(result.getToolUseId(), result.getName(), result.getContent(), result.isError()));
		}
		return new LlmMessage(Role.USER, parts);
	}
}
