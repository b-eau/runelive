package com.runelive.sidekick.context;

/** Completion state of a quest or miniquest. Uses generic names so no RuneLite types leak
 * into the portable core. */
public enum QuestStatus
{
	NOT_STARTED,
	IN_PROGRESS,
	COMPLETE
}
