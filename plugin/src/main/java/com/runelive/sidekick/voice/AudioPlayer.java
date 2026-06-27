package com.runelive.sidekick.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays raw PCM audio data through the system's default output device.
 *
 * Gemini TTS returns L16 PCM at 24 kHz, mono. In practice the byte order is
 * little-endian despite the L16 MIME type (which is technically big-endian per RFC 2586).
 */
class AudioPlayer
{
	private static final AudioFormat FORMAT = new AudioFormat(24_000, 16, 1, true, false);

	/** Plays {@code pcm} synchronously, blocking until playback is complete. */
	static void play(byte[] pcm) throws LineUnavailableException
	{
		if (pcm == null || pcm.length == 0)
		{
			return;
		}
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
		SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
		line.open(FORMAT);
		line.start();
		try
		{
			line.write(pcm, 0, pcm.length);
			line.drain();
		}
		finally
		{
			line.stop();
			line.close();
		}
	}
}
