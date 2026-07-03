package com.runelive.sidekick.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.runelive.sidekick.context.PlayerContext;
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
		String prompt = SystemPrompts.build(ironman());
		assertTrue(prompt.contains("Sidekick"));
		assertTrue(prompt.contains("PLAYER ACCOUNT SNAPSHOT"));
		assertTrue(prompt.contains("Zezima"));
		assertTrue("ironman status drives advice", prompt.contains("ironman ruleset"));
		assertTrue(prompt.contains("Attack 99"));
		assertTrue(prompt.contains("Zulrah 1500"));
	}

	@Test
	public void includesOutputStyleRules()
	{
		String prompt = SystemPrompts.build(ironman());
		assertTrue(prompt.contains("OUTPUT STYLE"));
		assertFalse(prompt.contains("READ ALOUD"));
	}

	@Test
	public void asksForConciseComparativeGearAwareAnswers()
	{
		String prompt = SystemPrompts.build(ironman());
		assertTrue("compares options with tradeoffs", prompt.contains("key tradeoffs"));
		assertTrue("quantifies xp rate", prompt.contains("xp/hr"));
		assertTrue("quantifies cost/profit", prompt.contains("gp/hr"));
		assertTrue("accounts for risk / AFK-ness", prompt.contains("AFK-ness"));
		assertTrue("leverages the player's owned gear", prompt.contains("equipment/inventory/bank"));
		assertTrue("flags first-time setup", prompt.contains("first-time setup"));
		assertTrue("pushes for brevity", prompt.contains("Be concise"));
		assertTrue("avoids tables that overflow the narrow panel",
			prompt.contains("Do NOT use Markdown tables"));
	}

	@Test
	public void embedsMemoryBlockWhenProvided()
	{
		String memory = "RECENT CONVERSATIONS WITH THIS PLAYER:\n- [abc123] \"How do I start Zulrah?\" — 2 days ago";
		String prompt = SystemPrompts.build(ironman(), memory);
		assertTrue(prompt.contains("RECENT CONVERSATIONS WITH THIS PLAYER"));
		assertTrue(prompt.contains("How do I start Zulrah?"));
	}

	@Test
	public void omitsMemoryBlockWhenBlank()
	{
		assertFalse(SystemPrompts.build(ironman(), null).contains("RECENT CONVERSATIONS"));
		assertFalse(SystemPrompts.build(ironman(), "   ").contains("RECENT CONVERSATIONS"));
	}

	@Test
	public void emphasisesWholeGameProgressionNotJustSlayer()
	{
		String prompt = SystemPrompts.build(ironman());
		assertTrue(prompt.contains("ACCOUNT PROGRESSION"));
		assertTrue("explicitly mentions Combat Achievements", prompt.contains("Combat Achievement"));
		assertTrue("explicitly mentions the collection log", prompt.contains("collection log"));
		assertTrue("tells the model not to default to Slayer",
			prompt.contains("do not default every answer to Slayer"));
	}
}
