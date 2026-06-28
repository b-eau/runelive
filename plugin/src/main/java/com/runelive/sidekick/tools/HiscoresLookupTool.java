package com.runelive.sidekick.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerContextSource;
import com.runelive.sidekick.context.PlayerNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * Tool: looks up the logged-in player's public OSRS hiscores (boss kill-counts and clue/minigame
 * scores) via WiseOldMan. These are <em>not</em> in the live-client snapshot, so this is how the
 * agent learns whether the player has actually done a boss or activity before — e.g. to know that a
 * just-unlocked boss has a kill-count of zero and give proper first-time advice.
 *
 * <p>The blocking network lookup runs on the agent's background thread (never the client thread).
 */
public class HiscoresLookupTool implements AgentTool
{
	private static final int MAX_BOSSES = 40;

	private final PlayerContextSource hiscores;
	private final Supplier<String> currentUsername;

	public HiscoresLookupTool(PlayerContextSource hiscores, Supplier<String> currentUsername)
	{
		this.hiscores = hiscores;
		this.currentUsername = currentUsername;
	}

	@Override
	public String name()
	{
		return "get_player_hiscores";
	}

	@Override
	public String description()
	{
		return "Look up the player's public OSRS hiscores (via WiseOldMan): boss kill-counts and "
			+ "clue/minigame scores, which are NOT in the live snapshot. Use this to check whether the "
			+ "player has done a boss or activity before, and how much — for example before giving "
			+ "first-time advice for content they may never have done. Any boss or activity not listed "
			+ "has a kill-count/score of zero or below the hiscores threshold. Optionally pass a filter "
			+ "to narrow the results to matching names.";
	}

	@Override
	public JsonObject inputSchema()
	{
		JsonObject filter = new JsonObject();
		filter.addProperty("type", "string");
		filter.addProperty("description", "Optional substring to narrow results, e.g. \"demon\" or \"zulrah\".");

		JsonObject properties = new JsonObject();
		properties.add("filter", filter);

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", new JsonArray());
		schema.addProperty("additionalProperties", false);
		return schema;
	}

	@Override
	public String execute(JsonObject input)
	{
		String username = currentUsername == null ? null : currentUsername.get();
		if (username == null || username.trim().isEmpty())
		{
			return "No player is logged in, so I can't look up hiscores.";
		}

		PlayerContext player;
		try
		{
			player = hiscores.fetch(username);
		}
		catch (PlayerNotFoundException e)
		{
			return "\"" + username + "\" is not tracked on the hiscores (WiseOldMan) — likely low-level "
				+ "or unranked, so treat boss kill-counts as effectively zero.";
		}
		catch (RuntimeException e)
		{
			return "Couldn't reach the hiscores service right now; proceed without exact kill-counts.";
		}

		String filter = optString(input, "filter").toLowerCase();
		return render(player, filter);
	}

	private static String render(PlayerContext player, String filter)
	{
		StringBuilder sb = new StringBuilder("Hiscores for ").append(player.getUsername()).append(":\n");

		List<Map.Entry<String, PlayerContext.BossStat>> bosses = player.topBosses(MAX_BOSSES);
		StringJoiner bossLine = new StringJoiner(", ");
		for (Map.Entry<String, PlayerContext.BossStat> e : bosses)
		{
			String pretty = prettify(e.getKey());
			if (filter.isEmpty() || pretty.toLowerCase().contains(filter) || e.getKey().contains(filter))
			{
				bossLine.add(pretty + " " + e.getValue().getKills());
			}
		}

		StringJoiner activityLine = new StringJoiner(", ");
		if (player.getActivities() != null)
		{
			for (Map.Entry<String, PlayerContext.ActivityStat> e : player.getActivities().entrySet())
			{
				String pretty = prettify(e.getKey());
				if (filter.isEmpty() || pretty.toLowerCase().contains(filter) || e.getKey().contains(filter))
				{
					activityLine.add(pretty + " " + e.getValue().getScore());
				}
			}
		}

		if (bossLine.length() == 0 && activityLine.length() == 0)
		{
			return filter.isEmpty()
				? "No ranked boss kill-counts or activity scores on the hiscores yet (all effectively zero)."
				: "Nothing matching \"" + filter + "\" is on the hiscores — kill-count/score is effectively zero.";
		}

		if (bossLine.length() > 0)
		{
			sb.append("- Boss KC: ").append(bossLine).append('\n');
		}
		if (activityLine.length() > 0)
		{
			sb.append("- Clues / minigames: ").append(activityLine).append('\n');
		}
		sb.append("(Anything not listed has a kill-count/score of zero or below the hiscores threshold.)");
		return sb.toString();
	}

	private static String prettify(String raw)
	{
		String[] parts = raw.replace('_', ' ').trim().split(" ");
		StringJoiner sj = new StringJoiner(" ");
		for (String p : parts)
		{
			if (p.isEmpty())
			{
				continue;
			}
			sj.add(Character.toUpperCase(p.charAt(0)) + p.substring(1));
		}
		return sj.toString();
	}

	private static String optString(JsonObject input, String key)
	{
		if (input == null || !input.has(key) || input.get(key).isJsonNull())
		{
			return "";
		}
		return input.get(key).getAsString().trim();
	}
}
