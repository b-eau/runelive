package com.runelive.sidekick.voice.realtime;

import com.google.gson.JsonObject;

/**
 * Callbacks from a {@link RealtimeVoiceSession}. All callbacks fire on the session's network thread,
 * so implementations must not block it for long (the session executes tool calls off-thread before
 * invoking {@link #onToolCall}).
 */
public interface RealtimeSessionListener
{
	/** The session is open and configured. */
	default void onConnected()
	{
	}

	/** Transcript of what the player said (for the panel and conversation history). */
	void onUserTranscript(String text);

	/** Text of the assistant's spoken reply (for the panel and conversation history). */
	void onAssistantText(String text);

	/** A frame of the assistant's spoken reply, as little-endian 16-bit PCM. */
	void onAssistantAudio(byte[] pcm16);

	/** The assistant finished its turn. */
	void onResponseDone();

	/**
	 * The model asked to call a tool. Execute it and return the textual result to send back to the
	 * model. Invoked off the network thread, so blocking work is fine here.
	 */
	String onToolCall(String name, JsonObject arguments);

	void onError(String message);

	void onClosed();
}
