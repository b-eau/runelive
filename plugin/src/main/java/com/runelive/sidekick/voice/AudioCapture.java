package com.runelive.sidekick.voice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import lombok.extern.slf4j.Slf4j;

/**
 * Captures microphone audio while {@link #start()} is held and converts it to a WAV byte array
 * when {@link #stopAndGetWav()} is called. Thread-safe: start/stop may be called from different
 * threads; audio capture runs on its own daemon thread.
 */
@Slf4j
class AudioCapture
{
	/** 16 kHz, 16-bit, mono — good enough for speech; keeps WAV size small (32 KB/s). */
	private static final AudioFormat FORMAT = new AudioFormat(16_000, 16, 1, true, false);
	private static final int CHUNK_BYTES = 1024;
	private static final long MAX_RECORD_MS = 30_000L;

	private final AtomicBoolean active = new AtomicBoolean(false);
	private volatile ByteArrayOutputStream buffer;
	private volatile Thread captureThread;

	/**
	 * Opens the default microphone and starts capturing audio.
	 *
	 * @throws LineUnavailableException if no microphone is available
	 */
	void start() throws LineUnavailableException
	{
		if (!active.compareAndSet(false, true))
		{
			return;
		}

		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		if (!AudioSystem.isLineSupported(info))
		{
			active.set(false);
			throw new LineUnavailableException("Microphone line not supported on this system");
		}

		TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
		line.open(FORMAT);
		line.start();

		ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
		buffer = buf;

		Thread t = new Thread(() -> {
			byte[] chunk = new byte[CHUNK_BYTES];
			long started = System.currentTimeMillis();
			try
			{
				while (active.get() && System.currentTimeMillis() - started < MAX_RECORD_MS)
				{
					int read = line.read(chunk, 0, chunk.length);
					if (read > 0)
					{
						buf.write(chunk, 0, read);
					}
				}
			}
			finally
			{
				line.stop();
				line.close();
			}
		}, "sidekick-audio-capture");
		t.setDaemon(true);
		captureThread = t;
		t.start();
	}

	/**
	 * Stops capture and blocks until the capture thread exits, then returns the recorded audio
	 * encoded as a WAV file. Returns an empty array if nothing was captured.
	 */
	byte[] stopAndGetWav() throws InterruptedException, IOException
	{
		active.set(false);
		Thread t = captureThread;
		if (t != null)
		{
			t.join(5_000);
			captureThread = null;
		}
		ByteArrayOutputStream buf = buffer;
		byte[] pcm = buf != null ? buf.toByteArray() : new byte[0];
		if (pcm.length == 0)
		{
			return pcm;
		}
		return toWav(pcm);
	}

	boolean isRecording()
	{
		return active.get();
	}

	private static byte[] toWav(byte[] pcm) throws IOException
	{
		AudioInputStream ais = new AudioInputStream(
			new ByteArrayInputStream(pcm), FORMAT, pcm.length / FORMAT.getFrameSize());
		ByteArrayOutputStream wav = new ByteArrayOutputStream(pcm.length + 64);
		AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav);
		return wav.toByteArray();
	}
}
