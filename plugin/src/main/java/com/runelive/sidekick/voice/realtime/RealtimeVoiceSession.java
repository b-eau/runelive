package com.runelive.sidekick.voice.realtime;

/**
 * A bidirectional realtime voice session with a voice-native agent: microphone audio streams up and
 * spoken replies (plus transcripts and tool calls) stream back via a {@link RealtimeSessionListener}.
 *
 * <p>This is the seam for pluggable realtime providers. {@link OpenAiRealtimeSession} speaks the
 * OpenAI Realtime protocol (used here for xAI Grok); the OpenAI Realtime and Gemini Live providers
 * slot in as additional implementations.
 */
public interface RealtimeVoiceSession
{
	/** Opens the session and applies {@code config}. Returns immediately; readiness is signalled via
	 *  {@link RealtimeSessionListener#onConnected()}. */
	void connect(RealtimeSessionConfig config, RealtimeSessionListener listener);

	/** Streams a frame of microphone audio (little-endian 16-bit PCM at the configured input rate). */
	void sendAudio(byte[] pcm16);

	/** Signals the end of the player's utterance (push-to-talk release) and asks for a reply. */
	void commitUserAudio();

	/** Closes the session and releases the connection. */
	void close();
}
