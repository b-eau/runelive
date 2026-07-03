package com.runelive.sidekick.context.client;

import net.runelite.api.gameval.VarbitID;

/**
 * Achievement diary areas and their per-tier completion varbit IDs.
 *
 * <p>Varbit value of {@code 1} means the tier is complete. {@code -1} means the tier does not
 * exist for that area (e.g. Karamja has no Easy/Medium/Hard varbit in the gameval constants).
 *
 * <p>Verified against {@code net.runelite.api.gameval.VarbitID} at build time; using constants
 * rather than magic numbers per the AGENTS.md convention.
 */
enum DiaryArea
{
	ARDOUGNE("Ardougne",
		VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE,
		VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
		VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE,
		VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE),

	DESERT("Desert",
		VarbitID.DESERT_DIARY_EASY_COMPLETE,
		VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
		VarbitID.DESERT_DIARY_HARD_COMPLETE,
		VarbitID.DESERT_DIARY_ELITE_COMPLETE),

	FALADOR("Falador",
		VarbitID.FALADOR_DIARY_EASY_COMPLETE,
		VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
		VarbitID.FALADOR_DIARY_HARD_COMPLETE,
		VarbitID.FALADOR_DIARY_ELITE_COMPLETE),

	FREMENNIK("Fremennik",
		VarbitID.FREMENNIK_DIARY_EASY_COMPLETE,
		VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
		VarbitID.FREMENNIK_DIARY_HARD_COMPLETE,
		VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE),

	KANDARIN("Kandarin",
		VarbitID.KANDARIN_DIARY_EASY_COMPLETE,
		VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
		VarbitID.KANDARIN_DIARY_HARD_COMPLETE,
		VarbitID.KANDARIN_DIARY_ELITE_COMPLETE),

	// Karamja only has an elite varbit in the public gameval constants.
	KARAMJA("Karamja", -1, -1, -1, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE),

	KOUREND("Kourend & Kebos",
		VarbitID.KOUREND_DIARY_EASY_COMPLETE,
		VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
		VarbitID.KOUREND_DIARY_HARD_COMPLETE,
		VarbitID.KOUREND_DIARY_ELITE_COMPLETE),

	LUMBRIDGE("Lumbridge & Draynor",
		VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE,
		VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
		VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE,
		VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE),

	MORYTANIA("Morytania",
		VarbitID.MORYTANIA_DIARY_EASY_COMPLETE,
		VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
		VarbitID.MORYTANIA_DIARY_HARD_COMPLETE,
		VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE),

	VARROCK("Varrock",
		VarbitID.VARROCK_DIARY_EASY_COMPLETE,
		VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
		VarbitID.VARROCK_DIARY_HARD_COMPLETE,
		VarbitID.VARROCK_DIARY_ELITE_COMPLETE),

	WESTERN("Western Provinces",
		VarbitID.WESTERN_DIARY_EASY_COMPLETE,
		VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
		VarbitID.WESTERN_DIARY_HARD_COMPLETE,
		VarbitID.WESTERN_DIARY_ELITE_COMPLETE),

	WILDERNESS("Wilderness",
		VarbitID.WILDERNESS_DIARY_EASY_COMPLETE,
		VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
		VarbitID.WILDERNESS_DIARY_HARD_COMPLETE,
		VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE);

	private final String displayName;
	private final int easyVarbit;
	private final int mediumVarbit;
	private final int hardVarbit;
	private final int eliteVarbit;

	DiaryArea(String displayName, int easyVarbit, int mediumVarbit, int hardVarbit, int eliteVarbit)
	{
		this.displayName = displayName;
		this.easyVarbit = easyVarbit;
		this.mediumVarbit = mediumVarbit;
		this.hardVarbit = hardVarbit;
		this.eliteVarbit = eliteVarbit;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	/** Returns the varbit ID for the given tier, or {@code -1} if this tier is not tracked. */
	public int varbitFor(DiaryTier tier)
	{
		switch (tier)
		{
			case EASY: return easyVarbit;
			case MEDIUM: return mediumVarbit;
			case HARD: return hardVarbit;
			case ELITE: return eliteVarbit;
			default: return -1;
		}
	}
}
