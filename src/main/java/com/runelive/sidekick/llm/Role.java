package com.runelive.sidekick.llm;

/** Conversation role. Tool results are carried in a {@link #USER} message (as both Anthropic and
 * Gemini expect), tool calls in an {@link #ASSISTANT} message. */
public enum Role
{
	USER,
	ASSISTANT
}
