package com.runelive.sidekick.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/** Renders a {@link PlayerContext} into the compact text block injected into the system prompt. */
final class PlayerContextFormatter
{
	// Skills listed in a stable, familiar order so the model always sees the same layout.
	private static final List<String> SKILL_ORDER = List.of(
		"attack", "strength", "defence", "hitpoints", "ranged", "prayer", "magic",
		"cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
		"smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
		"runecraft", "hunter", "construction", "sailing");

	private PlayerContextFormatter()
	{
	}

	static String toContextBlock(PlayerContext c)
	{
		boolean hasLiveData = c.getBank() != null || c.getQuests() != null
			|| c.getDiaries() != null || c.getCurrentHp() != null;
		StringBuilder sb = new StringBuilder();

		sb.append("PLAYER ACCOUNT SNAPSHOT");
		if (hasLiveData)
		{
			sb.append(" (live client data)\n");
		}
		else
		{
			sb.append(" (public hiscores — bank, quests, diaries and location unavailable)\n");
		}

		sb.append("- Username: ").append(c.getUsername()).append('\n');
		sb.append("- Account type: ").append(describeType(c)).append('\n');
		if (c.getBuild() != null && !c.getBuild().isEmpty())
		{
			sb.append("- Build: ").append(c.getBuild()).append('\n');
		}
		sb.append("- Combat level: ").append(c.getCombatLevel())
			.append("  |  Total level: ").append(c.getTotalLevel())
			.append("  |  Total XP: ").append(formatLong(c.getTotalExperience())).append('\n');
		if (c.getEfficientHoursPlayed() > 0 || c.getEfficientHoursBossed() > 0)
		{
			sb.append("- Efficiency: ").append(round(c.getEfficientHoursPlayed())).append(" EHP")
				.append(", ").append(round(c.getEfficientHoursBossed())).append(" EHB").append('\n');
		}
		if (c.getRegisteredAt() != null)
		{
			sb.append("- Tracked since: ").append(c.getRegisteredAt());
			if (c.getLastChangedAt() != null)
			{
				sb.append("  |  Last gain detected: ").append(c.getLastChangedAt());
			}
			sb.append('\n');
		}
		if (c.getCurrentLocation() != null)
		{
			sb.append("- Current location: ").append(c.getCurrentLocation()).append('\n');
		}

		// Live state (vitals, combat, inventory, equipment)
		appendCurrentState(sb, c);
		appendCombat(sb, c);
		appendEquipment(sb, c);
		appendInventory(sb, c);

		// Skills
		if (c.getSkills() != null && !c.getSkills().isEmpty())
		{
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
		}

		// Boss KCs (cloud source only — client doesn't expose these directly)
		if (c.getBosses() != null && !c.getBosses().isEmpty())
		{
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
		}

		// Minigames/clues
		if (c.getActivities() != null && !c.getActivities().isEmpty())
		{
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
		}

		// Quest log (client only)
		appendQuests(sb, c);

		// Achievement diaries (client only)
		appendDiaries(sb, c);

		// Bank summary (client only)
		appendBank(sb, c);

		return sb.toString();
	}

	private static void appendCurrentState(StringBuilder sb, PlayerContext c)
	{
		if (c.getCurrentHp() == null)
		{
			return;
		}
		sb.append("\nCURRENT STATE:\n");

		sb.append("- HP: ").append(c.getCurrentHp());
		if (c.getMaxHp() != null)
		{
			sb.append('/').append(c.getMaxHp());
		}
		if (c.getCurrentPrayer() != null)
		{
			sb.append(" | Prayer: ").append(c.getCurrentPrayer());
			if (c.getMaxPrayer() != null)
			{
				sb.append('/').append(c.getMaxPrayer());
			}
		}
		if (c.getRunEnergy() != null)
		{
			sb.append(" | Run: ").append(c.getRunEnergy()).append('%');
		}
		if (c.getWeight() != null)
		{
			sb.append(" | Weight: ").append(c.getWeight()).append(" kg");
		}
		if (c.getSpecialAttack() != null)
		{
			sb.append(" | Spec: ").append(c.getSpecialAttack()).append('%');
		}
		sb.append('\n');

		if (c.getSpellbook() != null)
		{
			sb.append("- Spellbook: ").append(c.getSpellbook());
			if (c.getActivePrayers() != null && !c.getActivePrayers().isEmpty())
			{
				sb.append(" | Active prayers: ").append(String.join(", ", c.getActivePrayers()));
			}
			sb.append('\n');
		}

		if (c.getWildernessLevel() != null)
		{
			sb.append("- Wilderness level: ").append(c.getWildernessLevel()).append('\n');
		}
		if (Boolean.TRUE.equals(c.getInInstance()))
		{
			sb.append("- Inside instanced region (raid / boss instance)\n");
		}

		if (c.getBoostedSkills() != null && !c.getBoostedSkills().isEmpty())
		{
			sb.append("- Boosted skills: ");
			StringJoiner boosts = new StringJoiner(", ");
			// Show in skill order where possible
			for (String skill : SKILL_ORDER)
			{
				Integer boosted = c.getBoostedSkills().get(skill);
				if (boosted == null)
				{
					continue;
				}
				PlayerContext.SkillStat base = c.skill(skill);
				if (base != null)
				{
					int delta = boosted - base.getLevel();
					String sign = delta >= 0 ? "+" : "";
					boosts.add(capitalize(skill) + " " + sign + delta + " (" + base.getLevel() + "→" + boosted + ")");
				}
				else
				{
					boosts.add(capitalize(skill) + " " + boosted);
				}
			}
			// Any skills not in SKILL_ORDER
			for (Map.Entry<String, Integer> e : c.getBoostedSkills().entrySet())
			{
				if (!SKILL_ORDER.contains(e.getKey()))
				{
					boosts.add(capitalize(e.getKey()) + " " + e.getValue());
				}
			}
			sb.append(boosts).append('\n');
		}
	}

	private static void appendCombat(StringBuilder sb, PlayerContext c)
	{
		boolean hasTarget = c.getNpcTarget() != null;
		boolean hasTask = c.getSlayerTask() != null;
		if (!hasTarget && !hasTask)
		{
			return;
		}
		sb.append("\nCOMBAT:\n");
		if (hasTarget)
		{
			sb.append("- Target: ").append(c.getNpcTarget()).append('\n');
		}
		if (hasTask)
		{
			sb.append("- Slayer task: ").append(c.getSlayerTask()).append('\n');
		}
	}

	private static void appendEquipment(StringBuilder sb, PlayerContext c)
	{
		if (c.getEquipment() == null || c.getEquipment().isEmpty())
		{
			return;
		}
		sb.append("\nEQUIPMENT:\n  ");
		// Display in a fixed, readable slot order
		String[] slotOrder = {"weapon", "shield", "head", "body", "legs", "cape",
			"amulet", "gloves", "boots", "ring", "ammo"};
		StringJoiner gear = new StringJoiner(" | ");
		for (String slot : slotOrder)
		{
			String item = c.getEquipment().get(slot);
			if (item != null)
			{
				gear.add(capitalize(slot) + ": " + item);
			}
		}
		// Any remaining slots not in the ordered list
		for (Map.Entry<String, String> e : c.getEquipment().entrySet())
		{
			boolean listed = false;
			for (String s : slotOrder)
			{
				if (s.equals(e.getKey()))
				{
					listed = true;
					break;
				}
			}
			if (!listed)
			{
				gear.add(capitalize(e.getKey()) + ": " + e.getValue());
			}
		}
		sb.append(gear).append('\n');
	}

	private static void appendInventory(StringBuilder sb, PlayerContext c)
	{
		if (c.getInventory() == null)
		{
			return;
		}
		sb.append("\nINVENTORY:\n  ");
		if (c.getInventory().isEmpty())
		{
			sb.append("(empty)");
		}
		else
		{
			StringJoiner items = new StringJoiner(", ");
			for (InventoryItem item : c.getInventory())
			{
				String label = item.getName() + (item.isNoted() ? " (noted)" : "");
				items.add(item.getQuantity() > 1 ? label + " x" + item.getQuantity() : label);
			}
			sb.append(items);
		}
		sb.append('\n');
	}

	private static void appendQuests(StringBuilder sb, PlayerContext c)
	{
		if (c.getQuests() == null)
		{
			return;
		}
		List<String> complete = c.getQuests().stream()
			.filter(q -> q.getStatus() == QuestStatus.COMPLETE).map(QuestEntry::getName).sorted().collect(Collectors.toList());
		List<String> inProgress = c.getQuests().stream()
			.filter(q -> q.getStatus() == QuestStatus.IN_PROGRESS).map(QuestEntry::getName).sorted().collect(Collectors.toList());

		sb.append("\nQUEST LOG:\n");
		sb.append("- Completed (").append(complete.size()).append("): ");
		if (complete.isEmpty())
		{
			sb.append("none");
		}
		else if (complete.size() <= 40)
		{
			sb.append(String.join(", ", complete));
		}
		else
		{
			// Too long to list all — show first 40 and count remainder
			sb.append(String.join(", ", complete.subList(0, 40)));
			sb.append(", … +").append(complete.size() - 40).append(" more");
		}
		sb.append('\n');
		if (!inProgress.isEmpty())
		{
			sb.append("- In progress: ").append(String.join(", ", inProgress)).append('\n');
		}
	}

	private static void appendDiaries(StringBuilder sb, PlayerContext c)
	{
		if (c.getDiaries() == null)
		{
			return;
		}
		sb.append("\nACHIEVEMENT DIARIES:\n");
		// Group by area for compact display
		Map<String, List<DiaryEntry>> byArea = c.getDiaries().stream()
			.collect(Collectors.groupingBy(DiaryEntry::getArea,
				java.util.LinkedHashMap::new, Collectors.toList()));
		for (Map.Entry<String, List<DiaryEntry>> entry : byArea.entrySet())
		{
			StringJoiner completed = new StringJoiner(", ");
			for (DiaryEntry d : entry.getValue())
			{
				if (d.isComplete())
				{
					completed.add(d.getTier());
				}
			}
			String completedStr = completed.toString();
			sb.append("- ").append(entry.getKey()).append(": ");
			sb.append(completedStr.isEmpty() ? "none" : completedStr);
			sb.append('\n');
		}
	}

	private static void appendBank(StringBuilder sb, PlayerContext c)
	{
		if (c.getBank() == null)
		{
			return;
		}
		sb.append("\nBANK (top items by quantity, up to 50):\n  ");
		List<BankItem> top = c.getBank().stream()
			.sorted(Comparator.comparingInt(BankItem::getQuantity).reversed())
			.limit(50)
			.collect(Collectors.toList());
		if (top.isEmpty())
		{
			sb.append("(empty)");
		}
		else
		{
			StringJoiner items = new StringJoiner(", ");
			for (BankItem item : top)
			{
				items.add(item.getName() + " x" + formatLong(item.getQuantity()));
			}
			sb.append(items);
		}
		sb.append('\n');
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
