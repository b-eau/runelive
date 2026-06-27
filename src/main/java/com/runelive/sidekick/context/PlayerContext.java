package com.runelive.sidekick.context;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * An immutable snapshot of everything the sidekick knows about a player's account.
 *
 * <p>Fields sourced from the community cloud APIs ({@link CloudPlayerContextSource} via WiseOldMan):
 * skills, boss kill-counts, minigame scores, account type/build, efficiency metrics, account age.
 *
 * <p>Fields sourced from the live RuneLite client ({@code ClientPlayerContextSource}):
 * {@link #bank}, {@link #quests}, {@link #diaries}, {@link #currentLocation}. These are
 * {@code null} when the cloud source is used, and populated by the plugin.
 *
 * <p>Use {@link PlayerContext#builder()} to construct; all nullable fields default to null.
 */
@Value
@Builder
public class PlayerContext
{
	String username;
	String accountType;       // e.g. "regular", "ironman", "hardcore", "ultimate", "group_ironman"
	String build;             // e.g. "main", "f2p", "lvl3", "def1", "zerker", "1def"
	int combatLevel;
	int totalLevel;
	long totalExperience;
	double efficientHoursPlayed;  // WiseOldMan EHP (0 when not available from cloud)
	double efficientHoursBossed;  // WiseOldMan EHB (0 when not available from cloud)
	String registeredAt;   // account creation (ISO-8601, nullable)
	String lastChangedAt;  // last detected gain (ISO-8601, nullable), proxy for recent activity

	/** Skill name -> stat. Skill names use OSRS conventions, e.g. "attack", "slayer". */
	Map<String, SkillStat> skills;
	/** Boss name -> kill stat, kills > 0 only. */
	Map<String, BossStat> bosses;
	/** Activity/minigame name -> score, ranked entries only (clues, LMS, soul wars, ...). */
	Map<String, ActivityStat> activities;

	// ── Client-only fields (null when populated from cloud APIs) ──────────────────────────────────

	/** Bank contents, sorted by quantity descending. {@code null} if the bank hasn't been opened
	 *  this session, or if the context came from cloud APIs. */
	List<BankItem> bank;

	/** All quests and their completion state. {@code null} if from cloud APIs. */
	List<QuestEntry> quests;

	/** Achievement diary tier completion (Easy/Medium/Hard/Elite per area).
	 *  {@code null} if from cloud APIs. */
	List<DiaryEntry> diaries;

	/** Human-readable current location, e.g. "Grand Exchange", "Slayer Tower".
	 *  {@code null} if from cloud APIs or player location is unknown. */
	String currentLocation;

	// ── Live client: immediate game state (null when from cloud APIs) ────────────────────────────

	/** Current hitpoints. {@code null} if from cloud APIs. */
	Integer currentHp;
	/** Maximum hitpoints (real level). {@code null} if from cloud APIs. */
	Integer maxHp;
	/** Current prayer points. {@code null} if from cloud APIs. */
	Integer currentPrayer;
	/** Maximum prayer points (real level). {@code null} if from cloud APIs. */
	Integer maxPrayer;
	/** Run energy 0–100 (%). {@code null} if from cloud APIs. */
	Integer runEnergy;
	/** Player weight in kg. {@code null} if from cloud APIs. */
	Integer weight;
	/** Special attack energy 0–100 (%). {@code null} if from cloud APIs. */
	Integer specialAttack;
	/** Active spellbook: "Standard", "Ancient", "Lunar", or "Arceuus". {@code null} if from cloud APIs. */
	String spellbook;
	/** Names of all currently active prayers. Empty list = none active. {@code null} if from cloud APIs. */
	List<String> activePrayers;
	/** Skills currently boosted/drained relative to real level: skill name → boosted level.
	 *  Only contains entries where boosted level differs from the real level.
	 *  {@code null} if from cloud APIs. */
	Map<String, Integer> boostedSkills;
	/** NPC the player is currently interacting with, e.g. "Vorkath (lvl 732)".
	 *  {@code null} if not in combat or from cloud APIs. */
	String npcTarget;
	/** Current slayer assignment, e.g. "87 × Dagannoth".
	 *  {@code null} if no task or from cloud APIs. */
	String slayerTask;
	/** Contents of the player's inventory (28 slots). {@code null} if from cloud APIs. */
	List<InventoryItem> inventory;
	/** Worn equipment by slot, e.g. "weapon" → "Twisted bow". {@code null} if from cloud APIs. */
	Map<String, String> equipment;
	/** Wilderness level 1–55+, or {@code null} if not in the Wilderness. */
	Integer wildernessLevel;
	/** {@code true} if the player is inside an instanced region (raids, boss rooms).
	 *  {@code null} if from cloud APIs. */
	Boolean inInstance;

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
		return skills == null ? null : skills.get(name.toLowerCase());
	}

	public int skillLevel(String name)
	{
		SkillStat stat = skill(name);
		return stat != null ? stat.getLevel() : 1;
	}

	public int bossKills(String name)
	{
		if (bosses == null)
		{
			return 0;
		}
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
