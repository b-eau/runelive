package com.runelive.sidekick;

/**
 * The spoken voices offered by xAI's Grok Voice Agent API. The wire value is the lowercase name.
 * See xAI's voice docs (Ara, Rex, Sal, Eve, Leo).
 */
public enum RealtimeVoice
{
	ARA("Ara"),
	REX("Rex"),
	SAL("Sal"),
	EVE("Eve"),
	LEO("Leo");

	private final String displayName;

	RealtimeVoice(String displayName)
	{
		this.displayName = displayName;
	}

	/** The value sent to the API (lowercase). */
	public String apiName()
	{
		return name().toLowerCase();
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
