package com.runelive.sidekick.llm;

/** Selectable chat-model backend. Add a new value plus an {@link LlmClient} implementation to
 * support another provider; nothing else in the app needs to change. */
public enum LlmProvider
{
	ANTHROPIC,
	GEMINI,
	XAI;

	public static LlmProvider fromString(String value)
	{
		if (value == null)
		{
			return ANTHROPIC;
		}
		String v = value.trim().toLowerCase();
		if (v.equals("gemini") || v.equals("google"))
		{
			return GEMINI;
		}
		if (v.equals("xai") || v.equals("grok") || v.equals("x.ai"))
		{
			return XAI;
		}
		return ANTHROPIC;
	}
}
