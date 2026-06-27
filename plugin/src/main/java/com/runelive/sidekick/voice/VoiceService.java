package com.runelive.sidekick.voice;

import com.runelive.sidekick.agent.AgentReply;
import com.runelive.sidekick.agent.AgentService;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerContextSource;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.llm.LlmMessage;
import com.runelive.sidekick.llm.Modality;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.LineUnavailableException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * Coordinates the push-to-talk voice pipeline:
 * microphone → Gemini STT → AgentService → (Gemini TTS → speakers).
 *
 * <p>All blocking work (audio capture, network calls, audio playback) executes on a
 * single daemon thread so neither the client thread nor the OkHttp pool is blocked.
 * An {@link AtomicBoolean} flag prevents a new capture from starting until the
 * previous pipeline run (including TTS playback) completes.
 */
@Slf4j
public class VoiceService
{
	/** Minimum WAV size (bytes) to treat as non-empty audio — avoids sending silence. */
	private static final int MIN_WAV_BYTES = 2_000;

	private final GeminiVoiceClient voiceClient;
	private final AgentService agentService;
	private final PlayerContextSource contextSource;
	private final ChatMessageManager chatMessages;
	private final boolean ttsEnabled;

	private final AudioCapture capture = new AudioCapture();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "sidekick-voice");
		t.setDaemon(true);
		return t;
	});
	private final AtomicBoolean busy = new AtomicBoolean(false);

	public VoiceService(
		GeminiVoiceClient voiceClient,
		AgentService agentService,
		PlayerContextSource contextSource,
		ChatMessageManager chatMessages,
		boolean ttsEnabled)
	{
		this.voiceClient = voiceClient;
		this.agentService = agentService;
		this.contextSource = contextSource;
		this.chatMessages = chatMessages;
		this.ttsEnabled = ttsEnabled;
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
	 * Stops microphone capture and launches the transcription → agent → TTS pipeline
	 * asynchronously. Safe to call from the client thread (AWT EDT).
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

			postMessage("<col=ffffff>You:</col> " + text);
			postMessage("Sidekick is thinking...");

			PlayerContext context;
			try
			{
				context = contextSource.fetch(null);
			}
			catch (PlayerNotFoundException e)
			{
				postMessage("<col=ff0000>No player logged in</col> — cannot personalise advice.");
				return;
			}

			AgentReply reply = agentService.chat(
				context, Modality.VOICE, List.of(LlmMessage.userText(text)),
				step -> postMessage("<col=888888>" + step + "</col>"));

			postMessage("<col=00bcd4>Sidekick:</col> " + reply.getText());

			if (ttsEnabled)
			{
				speakQuietly(reply.getText());
			}
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

	private void speakQuietly(String text)
	{
		try
		{
			byte[] pcm = voiceClient.synthesize(text);
			AudioPlayer.play(pcm);
		}
		catch (Exception e)
		{
			// TTS failure is non-fatal — text response is already shown in chat.
			log.debug("TTS failed, falling back to text only", e);
		}
	}

	private void postMessage(String message)
	{
		chatMessages.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage("[Sidekick] " + message)
			.build());
	}
}
