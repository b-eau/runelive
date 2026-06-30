package com.runelive.sidekick.voice;

import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import lombok.extern.slf4j.Slf4j;

/**
 * Captures microphone audio and pushes raw little-endian 16-bit PCM frames to a callback as they are
 * read, for streaming to a realtime voice agent. Unlike {@link AudioCapture} (which buffers a whole
 * utterance and encodes WAV on stop), this emits frames continuously while active.
 *
 * <p>Capture runs on its own daemon thread; {@link #start} and {@link #stop} may be called from any
 * thread (including the EDT). The frame callback fires on the capture thread.
 */
@Slf4j
public class StreamingMicrophone
{
	private static final int FRAME_BYTES = 3200; // ~67 ms at 24 kHz mono 16-bit

	private final AudioFormat format;
	private volatile boolean active;
	private volatile TargetDataLine line;
	private volatile Thread thread;

	public StreamingMicrophone(int sampleRateHz)
	{
		this.format = new AudioFormat(sampleRateHz, 16, 1, true, false);
	}

	/** Opens the default microphone and starts streaming PCM frames to {@code onFrame}. */
	public synchronized void start(Consumer<byte[]> onFrame) throws LineUnavailableException
	{
		if (active)
		{
			return;
		}
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
		if (!AudioSystem.isLineSupported(info))
		{
			throw new LineUnavailableException("Microphone line not supported for realtime capture");
		}
		TargetDataLine mic = (TargetDataLine) AudioSystem.getLine(info);
		mic.open(format);
		mic.start();
		line = mic;
		active = true;

		Thread t = new Thread(() ->
		{
			byte[] chunk = new byte[FRAME_BYTES];
			try
			{
				while (active)
				{
					int read = mic.read(chunk, 0, chunk.length);
					if (read > 0)
					{
						byte[] frame = new byte[read];
						System.arraycopy(chunk, 0, frame, 0, read);
						onFrame.accept(frame);
					}
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Realtime microphone capture stopped", e);
			}
			finally
			{
				mic.stop();
				mic.close();
			}
		}, "sidekick-realtime-mic");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	/** Stops capture. Safe to call repeatedly. */
	public synchronized void stop()
	{
		active = false;
		Thread t = thread;
		if (t != null)
		{
			t.interrupt();
			thread = null;
		}
		TargetDataLine l = line;
		if (l != null)
		{
			l.stop();
			line = null;
		}
	}

	public boolean isActive()
	{
		return active;
	}
}
