package com.runelive.sidekick.context.wiki;

import lombok.Value;

/** A single OSRS Wiki article summary. */
@Value
public class WikiResult
{
	boolean found;
	String title;
	String extract; // plain-text intro of the article (truncated)
	String url;

	public static WikiResult notFound(String query)
	{
		return new WikiResult(false, query, "", "");
	}
}
