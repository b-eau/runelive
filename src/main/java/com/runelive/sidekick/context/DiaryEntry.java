package com.runelive.sidekick.context;

import lombok.Value;

/** Completion state of one tier of an achievement diary. */
@Value
public class DiaryEntry
{
	/** e.g. "Ardougne", "Lumbridge & Draynor" */
	String area;
	/** "Easy", "Medium", "Hard", or "Elite" */
	String tier;
	boolean complete;
}
