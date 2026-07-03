package com.runelive.sidekick.context.client;

enum DiaryTier
{
	EASY("Easy"),
	MEDIUM("Medium"),
	HARD("Hard"),
	ELITE("Elite");

	private final String displayName;

	DiaryTier(String displayName)
	{
		this.displayName = displayName;
	}

	public String getDisplayName()
	{
		return displayName;
	}
}
