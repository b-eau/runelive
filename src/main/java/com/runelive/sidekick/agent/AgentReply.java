package com.runelive.sidekick.agent;

import com.runelive.sidekick.llm.StopReason;
import java.util.List;
import lombok.Value;

/** The agent's final answer for a turn, plus the tool calls it made and token usage. */
@Value
public class AgentReply
{
	String text;
	List<ToolInvocation> toolInvocations;
	StopReason stopReason;
	int inputTokens;
	int outputTokens;
}
