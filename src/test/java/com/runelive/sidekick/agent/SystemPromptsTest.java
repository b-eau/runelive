package com.runelive.sidekick.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.llm.Modality;
import java.util.Map;
import org.junit.Test;

public class SystemPromptsTest
{
	private static PlayerContext ironman()
	{
		return PlayerContext.builder()
			.username("Zezima").accountType("ironman").build("main")
			.combatLevel(100).totalLevel(1500).totalExperience(1_000_000L)
			.efficientHoursPlayed(10.0).efficientHoursBossed(5.0)
			.registeredAt("2015-01-01").lastChangedAt("2024-06-01")
			.skills(Map.of("attack", new PlayerContext.SkillStat(99, 13_034_431L, 50)))
			.bosses(Map.of("zulrah", new PlayerContext.BossStat(1500, 1000)))
			.activities(Map.of())
			.build();
	}

	@Test
	public void embedsPersonaAndAccountContext()
	{
		String prompt = SystemPrompts.build(ironman(), Modality.TEXT);
		assertTrue(prompt.contains("Sidekick"));
		assertTrue(prompt.contains("PLAYER ACCOUNT SNAPSHOT"));
		assertTrue(prompt.contains("Zezima"));
		assertTrue("ironman status drives advice", prompt.contains("ironman ruleset"));
		assertTrue(prompt.contains("Attack 99"));
		assertTrue(prompt.contains("Zulrah 1500"));
	}

	@Test
	public void textModeAllowsFormatting()
	{
		String prompt = SystemPrompts.build(ironman(), Modality.TEXT);
		assertTrue(prompt.contains("TEXT CHAT"));
		assertFalse(prompt.contains("READ ALOUD"));
	}

	@Test
	public void voiceModeForbidsMarkdownAndUrls()
	{
		String prompt = SystemPrompts.build(ironman(), Modality.VOICE);
		assertTrue(prompt.contains("VOICE CHAT"));
		assertTrue(prompt.contains("READ ALOUD"));
		assertTrue(prompt.toLowerCase().contains("no markdown"));
		assertTrue(prompt.toLowerCase().contains("url"));
	}
}
