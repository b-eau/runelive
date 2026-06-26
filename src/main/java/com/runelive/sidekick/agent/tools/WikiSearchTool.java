package com.runelive.sidekick.agent.tools;

import com.google.gson.JsonObject;
import com.runelive.sidekick.context.wiki.WikiClient;
import com.runelive.sidekick.context.wiki.WikiResult;

/** Tool: search the OSRS Wiki so the agent can ground answers in current game facts. */
public class WikiSearchTool implements AgentTool
{
	private final WikiClient wikiClient;

	public WikiSearchTool(WikiClient wikiClient)
	{
		this.wikiClient = wikiClient;
	}

	@Override
	public String name()
	{
		return "search_osrs_wiki";
	}

	@Override
	public String description()
	{
		return "Search the Old School RuneScape Wiki for game knowledge: quest requirements and "
			+ "rewards, boss strategies, item stats, skill training methods, achievement diary tasks, "
			+ "and mechanics. Use this to verify requirements and facts before advising, instead of "
			+ "relying on memory. Returns the intro of the best-matching article and its URL.";
	}

	@Override
	public JsonObject inputSchema()
	{
		return Schemas.singleString("query", "What to look up, e.g. 'Dragon Slayer II requirements'.");
	}

	@Override
	public String execute(JsonObject input)
	{
		String query = Schemas.optString(input, "query").trim();
		if (query.isEmpty())
		{
			return "No search query was provided.";
		}
		WikiResult result = wikiClient.search(query);
		if (!result.isFound())
		{
			return "No OSRS Wiki article found for \"" + query + "\".";
		}
		return result.getTitle() + ": " + result.getExtract() + "\nSource: " + result.getUrl();
	}
}
