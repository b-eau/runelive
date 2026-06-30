package com.runelive.sidekick.voice;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import lombok.extern.slf4j.Slf4j;

/**
 * Plays a stream of little-endian 16-bit PCM frames to the default speakers — used for a realtime
 * voice agent's spoken replies. Frames are queued from the network thread and drained on a dedicated
 * daemon thread so neither the client thread nor the websocket thread blocks on audio output.
 *
 * <p>This is audio <em>output</em> only (no game input is synthesised), so it does not run afoul of
 * the input-injection rules.
 */
@Slf4j
public class AudioPlayer
{
	private static final byte[] POISON = new byte[0];

	private final AudioFormat format;
	private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
	private volatile SourceDataLine line;
	private volatile Thread thread;
	private volatile boolean running;

	public AudioPlayer(int sampleRateHz)
	{
		this.format = new AudioFormat(sampleRateHz, 16, 1, true, false);
	}

	/** Opens the output line and begins draining queued audio. */
	public synchronized void start() throws LineUnavailableException
	{
		if (running)
		{
			return;
		}
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
		SourceDataLine out = (SourceDataLine) AudioSystem.getLine(info);
		out.open(format);
		out.start();
		line = out;
		running = true;

		Thread t = new Thread(this::drain, "sidekick-audio-player");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	/** Queues a PCM frame for playback. */
	public void enqueue(byte[] pcm)
	{
		if (running && pcm != null && pcm.length > 0)
		{
			queue.add(pcm);
		}
	}

	/** Drops any pending audio and flushes the line — used when the user interrupts ("barge-in"). */
	public void interrupt()
	{
		queue.clear();
		SourceDataLine l = line;
		if (l != null)
		{
			l.flush();
		}
	}

	/** Stops playback and releases the output line. */
	public synchronized void stop()
	{
		if (!running)
		{
			return;
		}
		running = false;
		queue.clear();
		queue.add(POISON);
		Thread t = thread;
		if (t != null)
		{
			t.interrupt();
			thread = null;
		}
	}

	private void drain()
	{
		SourceDataLine out = line;
		try
		{
			while (running)
			{
				byte[] frame = queue.take();
				if (frame == POISON || frame.length == 0)
				{
					continue;
				}
				out.write(frame, 0, frame.length);
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (RuntimeException e)
		{
			log.debug("Audio playback stopped", e);
		}
		finally
		{
			out.drain();
			out.stop();
			out.close();
		}
	}
}
