package com.runelive.sidekick;

import com.runelive.sidekick.llm.LlmProvider;

/**
 * The AI providers offered in the config dropdown. RuneLite renders any enum-typed {@code @ConfigItem}
 * as a combo box using {@link #toString()}, so this enum doubles as the picker and the source of each
 * provider's sensible default model.
 */
public enum LlmProviderOption
{
	GEMINI("Google Gemini", LlmProvider.GEMINI, "gemini-3.5-flash"),
	ANTHROPIC("Anthropic (Claude)", LlmProvider.ANTHROPIC, "claude-haiku-4-5-20251001"),
	XAI("xAI (Grok)", LlmProvider.XAI, "grok-4-fast-reasoning");

	private final String displayName;
	private final LlmProvider provider;
	private final String defaultModel;

	LlmProviderOption(String displayName, LlmProvider provider, String defaultModel)
	{
		this.displayName = displayName;
		this.provider = provider;
		this.defaultModel = defaultModel;
	}

	public LlmProvider provider()
	{
		return provider;
	}

	public String defaultModel()
	{
		return defaultModel;
	}

	/** Whether this provider can also power the voice speech-to-text path (currently Gemini only). */
	public boolean supportsVoice()
	{
		return this == GEMINI;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
