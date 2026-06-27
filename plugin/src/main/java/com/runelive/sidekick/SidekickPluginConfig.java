package com.runelive.sidekick;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("osrs-sidekick")
public interface SidekickPluginConfig extends Config
{
	@ConfigSection(
		name = "Connection",
		description = "LLM provider and credentials",
		position = 0)
	String connectionSection = "connection";

	@ConfigSection(
		name = "Voice",
		description = "Push-to-talk voice input",
		position = 1)
	String voiceSection = "voice";

	@ConfigItem(
		keyName = "provider",
		name = "AI Provider",
		description = "Which AI provider to use (anthropic or gemini).",
		section = connectionSection,
		position = 1)
	default String provider()
	{
		return "gemini";
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API Key",
		description = "API key for the selected AI provider. Keep this secret.",
		secret = true,
		section = connectionSection,
		position = 2)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "model",
		name = "Model",
		description = "Model name. Leave blank for the provider default (gemini-3.5-flash / claude-opus-4-8).",
		section = connectionSection,
		position = 3)
	default String model()
	{
		return "";
	}

	@ConfigItem(
		keyName = "maxTokens",
		name = "Max response tokens",
		description = "Maximum tokens in each AI response (larger = slower and more expensive).",
		section = connectionSection,
		position = 4)
	default int maxTokens()
	{
		return 2048;
	}

	// ── Voice ───────────────────────────────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "enableVoice",
		name = "Enable voice input",
		description = "Hold the push-to-talk key to speak your question. Requires a Gemini API key.",
		section = voiceSection,
		position = 0)
	default boolean enableVoice()
	{
		return false;
	}

	@ConfigItem(
		keyName = "voiceHotkey",
		name = "Push-to-talk key",
		description = "Hold this key while speaking, then release when done.",
		section = voiceSection,
		position = 1)
	default Keybind voiceHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "voiceApiKey",
		name = "Voice API key (Gemini)",
		description = "Gemini key for voice. Leave blank to reuse the main key when provider = gemini.",
		secret = true,
		section = voiceSection,
		position = 2)
	default String voiceApiKey()
	{
		return "";
	}
}
