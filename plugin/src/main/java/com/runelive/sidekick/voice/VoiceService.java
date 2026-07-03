package com.runelive.sidekick.voice;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sound.sampled.LineUnavailableException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * Coordinates the push-to-talk voice capture and transcription:
 * microphone → Gemini STT → {@code onTranscript}.
 *
 * <p>Once the speech is transcribed it is handed to the plugin's shared query path (via the
 * {@code onTranscript} callback), so a spoken question joins the same conversation thread as a
 * typed {@code ::sk} question or a panel follow-up. Status messages (listening, transcribing) are
 * posted to game chat so the player can follow along without leaving the game view.
 *
 * <p>All blocking work (audio capture, network calls) executes on a single daemon thread so
 * neither the client thread nor the OkHttp pool is blocked. An {@link AtomicBoolean} flag
 * prevents a new capture from starting until the previous pipeline run completes.
 */
@Slf4j
public class VoiceService implements VoiceBackend
{
	@Override
	public void pressToTalk()
	{
		startRecording();
	}

	@Override
	public void releaseToTalk()
	{
		stopAndProcess();
	}

	/** Minimum WAV size (bytes) to treat as non-empty audio — avoids sending silence. */
	private static final int MIN_WAV_BYTES = 2_000;

	/** A transcription with fewer than this many words is treated as an accidental hotkey tap and
	 *  discarded (a real question is essentially always two or more words). */
	private static final int MIN_WORDS = 2;

	private final GeminiVoiceClient voiceClient;
	private final ChatMessageManager chatMessages;
	private final Consumer<String> onTranscript;

	private final AudioCapture capture = new AudioCapture();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "sidekick-voice");
		t.setDaemon(true);
		return t;
	});
	private final AtomicBoolean busy = new AtomicBoolean(false);

	public VoiceService(
		GeminiVoiceClient voiceClient,
		ChatMessageManager chatMessages,
		Consumer<String> onTranscript)
	{
		this.voiceClient = voiceClient;
		this.chatMessages = chatMessages;
		this.onTranscript = onTranscript;
	}

	/**
	 * Starts microphone capture. Safe to call from the client thread (AWT EDT).
	 * No-op if a previous pipeline run is still in progress.
	 */
	public void startRecording()
	{
		if (!busy.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			capture.start();
			postMessage("<col=ffff00>Listening...</col> (release key when done)");
		}
		catch (LineUnavailableException e)
		{
			busy.set(false);
			postMessage("<col=ff0000>Microphone unavailable:</col> " + e.getMessage());
		}
	}

	/**
	 * Stops microphone capture and launches the transcription pipeline asynchronously.
	 * Safe to call from the client thread (AWT EDT).
	 */
	public void stopAndProcess()
	{
		if (!capture.isRecording())
		{
			busy.set(false);
			return;
		}
		executor.submit(this::runPipeline);
	}

	/** Stops any in-flight capture and shuts down the executor. Call from plugin shutDown(). */
	public void shutdown()
	{
		executor.shutdownNow();
		try
		{
			if (capture.isRecording())
			{
				capture.stopAndGetWav();
			}
		}
		catch (Exception e)
		{
			log.debug("Error stopping audio capture on shutdown", e);
		}
	}

	// ── Private ──────────────────────────────────────────────────────────────────────────────────

	private void runPipeline()
	{
		try
		{
			byte[] wav = capture.stopAndGetWav();
			if (wav.length < MIN_WAV_BYTES)
			{
				postMessage("Nothing heard — try holding the key a bit longer.");
				return;
			}

			postMessage("Transcribing...");
			String text = voiceClient.transcribe(wav);
			if (text.isEmpty())
			{
				postMessage("<col=ff0000>Could not understand the audio.</col> Please try again.");
				return;
			}
			if (isTooShort(text))
			{
				postMessage("Ignored that — too short to be a question. Hold the key and ask a full sentence.");
				return;
			}

			postMessage("<col=ffffff>You:</col> " + text);
			onTranscript.accept(text);
		}
		catch (Exception e)
		{
			log.debug("Voice pipeline error", e);
			postMessage("<col=ff0000>Voice error:</col> " + e.getMessage());
		}
		finally
		{
			busy.set(false);
		}
	}

	private void postMessage(String message)
	{
		chatMessages.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage("[Sidekick] " + message)
			.build());
	}

	/** True when a transcription is too short to be a genuine question (accidental hotkey tap). */
	static boolean isTooShort(String text)
	{
		if (text == null)
		{
			return true;
		}
		String trimmed = text.trim();
		return trimmed.isEmpty() || trimmed.split("\\s+").length < MIN_WORDS;
	}
}
