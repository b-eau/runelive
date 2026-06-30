package com.runelive.sidekick.voice;

/**
 * A push-to-talk voice backend, driven uniformly by the plugin's hotkey. Implementations decide what
 * happens between key-down and key-up:
 *
 * <ul>
 *   <li>{@link VoiceService} transcribes the captured speech to text and runs the normal text
 *       agent.</li>
 *   <li>{@code RealtimeVoiceBackend} streams the audio to a voice-native agent and plays the spoken
 *       reply back, with no transcription step.</li>
 * </ul>
 *
 * The plugin selects one implementation based on the configured {@link com.runelive.sidekick.VoiceMode}
 * and wires the same hotkey to {@link #pressToTalk()} / {@link #releaseToTalk()}.
 */
public interface VoiceBackend
{
	/** Hotkey pressed — begin capturing/streaming the player's speech. Safe to call on the EDT. */
	void pressToTalk();

	/** Hotkey released — finish the utterance and produce a response. Safe to call on the EDT. */
	void releaseToTalk();

	/** Releases all resources (microphone, sockets, threads). Call from the plugin's shutDown(). */
	void shutdown();
}
