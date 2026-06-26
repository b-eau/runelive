package com.runelive.sidekick.llm;

/** Selectable chat-model backend. Add a new value plus an {@link LlmClient} implementation to
 * support another provider; nothing else in the app needs to change. */
public enum LlmProvider
{
	ANTHROPIC,
	GEMINI;

	public static LlmProvider fromString(String value)
	{
		if (value != null && value.trim().equalsIgnoreCase("gemini"))
		{
			return GEMINI;
		}
		return ANTHROPIC;
	}
}
