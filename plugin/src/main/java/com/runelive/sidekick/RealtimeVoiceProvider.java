package com.runelive.sidekick;

/**
 * Provider for the realtime audio-to-audio voice agent (used when {@link VoiceMode#REALTIME} is
 * selected). New providers slot in by adding a value here and a matching
 * {@link com.runelive.sidekick.voice.realtime.RealtimeVoiceSession} implementation; the OpenAI
 * Realtime API and the Gemini Live API are the obvious next additions.
 */
public enum RealtimeVoiceProvider
{
	XAI("xAI Grok");
	// Future: OPENAI("OpenAI Realtime"), GEMINI("Gemini Live");

	private final String displayName;

	RealtimeVoiceProvider(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
