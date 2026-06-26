package com.runelive.sidekick.context;

import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * An immutable snapshot of everything the sidekick knows about a player's account.
 *
 * <p>This is the "personalization" payload injected into the model's context. Today it is
 * populated from public community APIs (the cloud subset — skills, boss kill-counts, minigame
 * scores, account type/build, efficiency metrics, account age and recent activity). When the
 * code is restructured into a RuneLite plugin, a client-backed {@link PlayerContextSource} will
 * fill in the rest of the vision — bank contents, current location, quest log, achievement
 * diaries, daily playtime — without changing this model's consumers.
 */
@Value
public class PlayerContext
{
	String username;
	String accountType;       // e.g. "regular", "ironman", "hardcore", "ultimate", "group_ironman"
	String build;             // e.g. "main", "f2p", "lvl3", "def1", "zerker", "1def"
	int combatLevel;
	int totalLevel;
	long totalExperience;
	double efficientHoursPlayed;  // WiseOldMan EHP
	double efficientHoursBossed;  // WiseOldMan EHB
	String registeredAt;   // account creation (ISO-8601, nullable), for "account lifetime"
	String lastChangedAt;  // last detected gain (ISO-8601, nullable), a proxy for recent activity

	/** Skill name -> stat. Skill names use OSRS conventions, e.g. "attack", "slayer". */
	Map<String, SkillStat> skills;
	/** Boss name -> kill stat, kills > 0 only. */
	Map<String, BossStat> bosses;
	/** Activity/minigame name -> score, ranked entries only (clues, LMS, soul wars, ...). */
	Map<String, ActivityStat> activities;

	@Value
	public static class SkillStat
	{
		int level;
		long experience;
		long rank;
	}

	@Value
	public static class BossStat
	{
		int kills;
		long rank;
	}

	@Value
	public static class ActivityStat
	{
		int score;
		long rank;
	}

	public boolean isIronman()
	{
		if (accountType == null)
		{
			return false;
		}
		String t = accountType.toLowerCase();
		return t.contains("ironman") || t.equals("hardcore") || t.equals("ultimate") || t.equals("group_ironman");
	}

	/** Returns the skill stat, or {@code null} if the skill is unknown. */
	public SkillStat skill(String name)
	{
		return skills.get(name.toLowerCase());
	}

	public int skillLevel(String name)
	{
		SkillStat stat = skill(name);
		return stat != null ? stat.getLevel() : 1;
	}

	public int bossKills(String name)
	{
		BossStat stat = bosses.get(name.toLowerCase());
		return stat != null ? stat.getKills() : 0;
	}

	/**
	 * Renders a compact, model-friendly description of the account for the system prompt. Kept
	 * terse on purpose: the model reasons better over a tight, well-labelled summary than a giant
	 * JSON dump, and it keeps token cost (and thus per-message cost) down.
	 */
	public String toContextBlock()
	{
		return PlayerContextFormatter.toContextBlock(this);
	}

	/** Top {@code n} bosses by kill-count, most-killed first. */
	public List<Map.Entry<String, BossStat>> topBosses(int n)
	{
		return PlayerContextFormatter.topEntries(bosses, n, (a, b) -> Integer.compare(b.getKills(), a.getKills()));
	}
}
