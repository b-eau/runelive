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
		description = "Push-to-talk voice input (transcription or a realtime voice agent)",
		position = 1)
	String voiceSection = "voice";

	@ConfigItem(
		keyName = "provider",
		name = "AI Provider",
		description = "Which AI provider powers the sidekick.",
		section = connectionSection,
		position = 1)
	default LlmProviderOption provider()
	{
		return LlmProviderOption.GEMINI;
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
		description = "Model name. Leave blank to use the selected provider's default "
			+ "(gemini-3.5-flash / claude-haiku-4-5-20251001 / grok-4-fast-reasoning).",
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

	@ConfigItem(
		keyName = "showToolCallsInChat",
		name = "Show tool calls in chat",
		description = "Post the sidekick's live tool lookups (wiki, prices, hiscores) to your game chat "
			+ "as it works. The sidebar shows these regardless.",
		section = connectionSection,
		position = 5)
	default boolean showToolCallsInChat()
	{
		return true;
	}

	// ── Voice ───────────────────────────────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "voiceMode",
		name = "Voice mode",
		description = "Off, push-to-talk transcription (speech → text → the normal text agent, using "
			+ "Gemini for transcription), or a realtime voice agent (audio-to-audio, no transcription "
			+ "step). Both modes use the push-to-talk key below.",
		section = voiceSection,
		position = 0)
	default VoiceMode voiceMode()
	{
		return VoiceMode.OFF;
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
		keyName = "realtimeProvider",
		name = "Realtime provider",
		description = "Which voice-native agent to use in Realtime mode.",
		section = voiceSection,
		position = 2)
	default RealtimeVoiceProvider realtimeProvider()
	{
		return RealtimeVoiceProvider.XAI;
	}

	@ConfigItem(
		keyName = "realtimeVoice",
		name = "Realtime voice",
		description = "Which Grok voice speaks in Realtime mode.",
		section = voiceSection,
		position = 3)
	default RealtimeVoice realtimeVoice()
	{
		return RealtimeVoice.EVE;
	}

	@ConfigItem(
		keyName = "realtimeApiKey",
		name = "Realtime API key",
		description = "API key for the realtime voice provider (Realtime mode only). Leave blank to "
			+ "reuse your main API key when it matches the realtime provider (e.g. provider = xAI).",
		secret = true,
		section = voiceSection,
		position = 4)
	default String realtimeApiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "voiceApiKey",
		name = "Transcription API key (Gemini)",
		description = "Transcription mode only. Needed when your AI Provider is NOT Gemini; leave blank "
			+ "to reuse your main API key when the provider is Gemini.",
		secret = true,
		section = voiceSection,
		position = 5)
	default String voiceApiKey()
	{
		return "";
	}
}
