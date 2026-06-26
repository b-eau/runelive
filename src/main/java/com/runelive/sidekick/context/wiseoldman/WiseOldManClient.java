package com.runelive.sidekick.context.wiseoldman;

import com.google.gson.JsonObject;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.http.HttpException;
import com.runelive.sidekick.http.HttpJson;
import com.runelive.sidekick.http.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;

/**
 * Client for the <a href="https://wiseoldman.net">WiseOldMan</a> player API
 * ({@code GET /v2/players/{username}}), which aggregates a player's hiscores into a single rich
 * document: skills, boss kill-counts, minigame/clue scores, account type and build, efficiency
 * metrics (EHP/EHB) and account timestamps.
 *
 * <p>The client is throttled by a {@link RateLimiter} so a chatty conversation never bursts the
 * community service. Caching is layered on top in {@link com.runelive.sidekick.context.CloudPlayerContextSource}.
 */
@Slf4j
public class WiseOldManClient
{
	private final HttpJson http;
	private final HttpUrl baseUrl;
	private final RateLimiter rateLimiter;

	public WiseOldManClient(HttpJson http, HttpUrl baseUrl, RateLimiter rateLimiter)
	{
		this.http = http;
		this.baseUrl = baseUrl;
		this.rateLimiter = rateLimiter;
	}

	/**
	 * Fetches and maps a player's full context.
	 *
	 * @throws PlayerNotFoundException if WiseOldMan has no record of the player (HTTP 404)
	 * @throws HttpException on any other upstream failure
	 */
	public PlayerContext fetchPlayer(String username)
	{
		HttpUrl url = baseUrl.newBuilder()
			.addPathSegment("players")
			.addPathSegment(username)
			.build();

		JsonObject root;
		try
		{
			root = http.getObject(url, rateLimiter);
		}
		catch (HttpException e)
		{
			if (e.statusCode() == 404)
			{
				throw new PlayerNotFoundException(username);
			}
			throw e;
		}

		return map(username, root);
	}

	private PlayerContext map(String requested, JsonObject root)
	{
		String displayName = Json.optString(root, "displayName", requested);

		Map<String, PlayerContext.SkillStat> skills = new LinkedHashMap<>();
		Map<String, PlayerContext.BossStat> bosses = new LinkedHashMap<>();
		Map<String, PlayerContext.ActivityStat> activities = new LinkedHashMap<>();

		int totalLevel = 0;
		long overallExp = Json.optLong(root, "exp", 0);

		JsonObject snapshot = Json.optObject(root, "latestSnapshot");
		JsonObject data = snapshot == null ? null : Json.optObject(snapshot, "data");
		if (data != null)
		{
			JsonObject skillData = Json.optObject(data, "skills");
			if (skillData != null)
			{
				for (Map.Entry<String, com.google.gson.JsonElement> e : skillData.entrySet())
				{
					JsonObject s = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
					if (s == null)
					{
						continue;
					}
					int level = Json.optInt(s, "level", 1);
					long exp = Json.optLong(s, "experience", 0);
					long rank = Json.optLong(s, "rank", -1);
					if ("overall".equals(e.getKey()))
					{
						totalLevel = level;
						if (exp > 0)
						{
							overallExp = exp;
						}
						continue;
					}
					skills.put(e.getKey(), new PlayerContext.SkillStat(level, exp, rank));
				}
			}

			JsonObject bossData = Json.optObject(data, "bosses");
			if (bossData != null)
			{
				for (Map.Entry<String, com.google.gson.JsonElement> e : bossData.entrySet())
				{
					JsonObject b = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
					if (b == null)
					{
						continue;
					}
					int kills = Json.optInt(b, "kills", -1);
					if (kills > 0)
					{
						bosses.put(e.getKey(), new PlayerContext.BossStat(kills, Json.optLong(b, "rank", -1)));
					}
				}
			}

			JsonObject activityData = Json.optObject(data, "activities");
			if (activityData != null)
			{
				for (Map.Entry<String, com.google.gson.JsonElement> e : activityData.entrySet())
				{
					JsonObject a = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
					if (a == null)
					{
						continue;
					}
					int score = Json.optInt(a, "score", -1);
					if (score > 0)
					{
						activities.put(e.getKey(), new PlayerContext.ActivityStat(score, Json.optLong(a, "rank", -1)));
					}
				}
			}
		}

		return new PlayerContext(
			displayName,
			Json.optString(root, "type", "regular"),
			Json.optString(root, "build", ""),
			Json.optInt(root, "combatLevel", 0),
			totalLevel,
			overallExp,
			Json.optDouble(root, "ehp", 0),
			Json.optDouble(root, "ehb", 0),
			Json.optString(root, "registeredAt", null),
			Json.optString(root, "lastChangedAt", null),
			skills,
			bosses,
			activities);
	}
}
