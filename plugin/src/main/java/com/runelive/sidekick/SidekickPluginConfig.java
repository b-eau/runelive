package com.runelive.sidekick;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("osrs-sidekick")
public interface SidekickPluginConfig extends Config
{
	@ConfigSection(
		name = "Connection",
		description = "LLM provider and credentials",
		position = 0)
	String connectionSection = "connection";

	@ConfigItem(
		keyName = "enableSidekick",
		name = "Enable AI Sidekick",
		description = "Activates the AI chat sidekick, which uses an external AI service.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = connectionSection,
		position = 0)
	default boolean enableSidekick()
	{
		return false;
	}

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
}
