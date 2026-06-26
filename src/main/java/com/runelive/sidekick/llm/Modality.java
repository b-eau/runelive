package com.runelive.sidekick.llm;

/**
 * The conversation modality. The system prompt is tuned per-modality: {@link #VOICE} replies are
 * spoken aloud by the browser, so they must be short, plain and free of markdown/links; {@link #TEXT}
 * replies are rendered on screen and may use light formatting and include item names and prices.
 */
public enum Modality
{
	TEXT,
	VOICE;

	public static Modality fromString(String value)
	{
		if (value != null && value.trim().equalsIgnoreCase("voice"))
		{
			return VOICE;
		}
		return TEXT;
	}
}
