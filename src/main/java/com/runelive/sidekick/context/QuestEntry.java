package com.runelive.sidekick.context;

import lombok.Value;

/** A quest and its current completion state. */
@Value
public class QuestEntry
{
	String name;
	QuestStatus status;
}
