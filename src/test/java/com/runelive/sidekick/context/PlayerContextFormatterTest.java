package com.runelive.sidekick.context;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PlayerContextFormatterTest
{
	private static PlayerContext.PlayerContextBuilder base()
	{
		return PlayerContext.builder()
			.username("Zezima").accountType("regular").build("main")
			.combatLevel(100).totalLevel(1500).totalExperience(1_000_000L)
			.skills(Map.of("attack", new PlayerContext.SkillStat(99, 13_034_431L, 50)))
			.bosses(Map.of()).activities(Map.of());
	}

	@Test
	public void rendersCombatAchievementsAndCollectionLog()
	{
		Map<String, String> tiers = new LinkedHashMap<>();
		tiers.put("Easy", "complete");
		tiers.put("Medium", "in progress");
		tiers.put("Hard", "not started");

		String block = base()
			.combatTaskPoints(540)
			.combatTaskTiers(tiers)
			.collectionLogObtained(567)
			.collectionLogTotal(1477)
			.build()
			.toContextBlock();

		assertTrue(block.contains("COMBAT ACHIEVEMENTS"));
		assertTrue(block.contains("Points: 540"));
		assertTrue(block.contains("Easy complete"));
		assertTrue(block.contains("Medium in progress"));
		assertTrue(block.contains("COLLECTION LOG: 567 / 1477 unique items"));
	}

	@Test
	public void diariesShowCompletedAndRemainingTiers()
	{
		List<DiaryEntry> diaries = List.of(
			new DiaryEntry("Ardougne", "Easy", true),
			new DiaryEntry("Ardougne", "Medium", false),
			new DiaryEntry("Ardougne", "Hard", false),
			new DiaryEntry("Ardougne", "Elite", false),
			new DiaryEntry("Varrock", "Easy", true),
			new DiaryEntry("Varrock", "Medium", true),
			new DiaryEntry("Varrock", "Hard", true),
			new DiaryEntry("Varrock", "Elite", true));

		String block = base().diaries(diaries).build().toContextBlock();

		assertTrue(block.contains("ACHIEVEMENT DIARIES (done / remaining)"));
		assertTrue("remaining tiers are surfaced so the model sees what's left",
			block.contains("Ardougne: Easy done; Medium, Hard, Elite remaining"));
		assertTrue("a fully-done area is marked complete", block.contains("Varrock: all complete"));
	}

	@Test
	public void omitsProgressionSectionsWhenUnknown()
	{
		String block = base().build().toContextBlock();
		assertFalse(block.contains("COMBAT ACHIEVEMENTS"));
		assertFalse(block.contains("COLLECTION LOG"));
	}
}
