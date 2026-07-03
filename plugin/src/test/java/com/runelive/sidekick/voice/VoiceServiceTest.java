package com.runelive.sidekick.voice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceServiceTest
{
	@Test
	public void discardsEmptyOrSingleWordTranscriptions()
	{
		assertTrue(VoiceService.isTooShort(null));
		assertTrue(VoiceService.isTooShort(""));
		assertTrue(VoiceService.isTooShort("   "));
		assertTrue("a stray single word is almost always an accidental tap",
			VoiceService.isTooShort("Zulrah"));
		assertTrue(VoiceService.isTooShort("  okay  "));
	}

	@Test
	public void keepsRealQuestions()
	{
		assertFalse(VoiceService.isTooShort("max hit?"));
		assertFalse(VoiceService.isTooShort("where should I slay greater demons"));
	}
}
