package com.runelive.sidekick.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Renders a {@link PlayerContext} into the compact text block injected into the system prompt. */
final class PlayerContextFormatter
{
	// Skills listed in a stable, familiar order so the model always sees the same layout.
	private static final List<String> SKILL_ORDER = List.of(
		"attack", "strength", "defence", "hitpoints", "ranged", "prayer", "magic",
		"cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
		"smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
		"runecraft", "hunter", "construction");

	private PlayerContextFormatter()
	{
	}

	static String toContextBlock(PlayerContext c)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("PLAYER ACCOUNT SNAPSHOT (from public hiscores; bank, quests, diaries and location are not yet available)\n");
		sb.append("- Username: ").append(c.getUsername()).append('\n');
		sb.append("- Account type: ").append(describeType(c)).append('\n');
		if (c.getBuild() != null && !c.getBuild().isEmpty())
		{
			sb.append("- Build: ").append(c.getBuild()).append('\n');
		}
		sb.append("- Combat level: ").append(c.getCombatLevel())
			.append("  |  Total level: ").append(c.getTotalLevel())
			.append("  |  Total XP: ").append(formatLong(c.getTotalExperience())).append('\n');
		sb.append("- Efficiency: ").append(round(c.getEfficientHoursPlayed())).append(" EHP")
			.append(", ").append(round(c.getEfficientHoursBossed())).append(" EHB").append('\n');
		if (c.getRegisteredAt() != null)
		{
			sb.append("- Tracked since: ").append(c.getRegisteredAt());
			if (c.getLastChangedAt() != null)
			{
				sb.append("  |  Last gain detected: ").append(c.getLastChangedAt());
			}
			sb.append('\n');
		}

		sb.append("\nSKILLS (level):\n");
		StringJoiner skillLine = new StringJoiner(", ");
		for (String skill : SKILL_ORDER)
		{
			PlayerContext.SkillStat stat = c.getSkills().get(skill);
			if (stat != null)
			{
				skillLine.add(capitalize(skill) + " " + stat.getLevel());
			}
		}
		sb.append(skillLine.toString().isEmpty() ? "  (no skill data)" : "  " + skillLine).append('\n');

		List<Map.Entry<String, PlayerContext.BossStat>> topBosses = topEntries(
			c.getBosses(), 12, Comparator.comparingInt(b -> -b.getKills()));
		if (!topBosses.isEmpty())
		{
			sb.append("\nNOTABLE BOSS KILL-COUNTS:\n  ");
			StringJoiner bossLine = new StringJoiner(", ");
			for (Map.Entry<String, PlayerContext.BossStat> e : topBosses)
			{
				bossLine.add(prettyName(e.getKey()) + " " + e.getValue().getKills());
			}
			sb.append(bossLine).append('\n');
		}

		List<Map.Entry<String, PlayerContext.ActivityStat>> activities = topEntries(
			c.getActivities(), 10, Comparator.comparingInt(a -> -a.getScore()));
		if (!activities.isEmpty())
		{
			sb.append("\nMINIGAMES / CLUES (score):\n  ");
			StringJoiner actLine = new StringJoiner(", ");
			for (Map.Entry<String, PlayerContext.ActivityStat> e : activities)
			{
				actLine.add(prettyName(e.getKey()) + " " + e.getValue().getScore());
			}
			sb.append(actLine).append('\n');
		}

		return sb.toString();
	}

	static <V> List<Map.Entry<String, V>> topEntries(Map<String, V> map, int n, Comparator<V> byValueDesc)
	{
		List<Map.Entry<String, V>> entries = new ArrayList<>(map.entrySet());
		entries.sort(Map.Entry.comparingByValue(byValueDesc));
		return entries.subList(0, Math.min(n, entries.size()));
	}

	private static String describeType(PlayerContext c)
	{
		String type = c.getAccountType() == null ? "regular" : c.getAccountType();
		String pretty = prettyName(type);
		return c.isIronman() ? pretty + " (ironman ruleset — cannot use the Grand Exchange or trade)" : pretty;
	}

	private static String prettyName(String raw)
	{
		String spaced = raw.replace('_', ' ').trim();
		if (spaced.isEmpty())
		{
			return spaced;
		}
		String[] parts = spaced.split(" ");
		StringJoiner sj = new StringJoiner(" ");
		for (String p : parts)
		{
			sj.add(capitalize(p));
		}
		return sj.toString();
	}

	private static String capitalize(String s)
	{
		if (s.isEmpty())
		{
			return s;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String round(double v)
	{
		return String.format("%.1f", v);
	}

	private static String formatLong(long v)
	{
		return String.format("%,d", v);
	}
}
