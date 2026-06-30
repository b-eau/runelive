package com.runelive.sidekick;

/**
 * How push-to-talk voice is handled. Rendered as a dropdown in the config.
 *
 * <ul>
 *   <li>{@link #TRANSCRIPTION} — the existing pipeline: capture speech, transcribe it to text
 *       (Gemini STT), then run the normal text agent and show the answer in the sidebar.</li>
 *   <li>{@link #REALTIME} — a voice-native, audio-to-audio agent (e.g. Grok): speech streams to the
 *       model and spoken replies stream back, with no separate transcription step.</li>
 * </ul>
 */
public enum VoiceMode
{
	OFF("Off"),
	TRANSCRIPTION("Push-to-talk (transcribe → text agent)"),
	REALTIME("Realtime voice agent");

	private final String displayName;

	VoiceMode(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
