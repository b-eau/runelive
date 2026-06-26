package com.runelive.sidekick.context.wiki;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.http.HttpJson;
import com.runelive.sidekick.http.Json;
import java.util.Locale;
import okhttp3.HttpUrl;

/**
 * Client for the OSRS Wiki MediaWiki API. Given a free-text query it returns the intro extract of
 * the best-matching article, letting the agent ground its answers about game mechanics,
 * requirements and strategy in the wiki rather than relying on (possibly stale) memory.
 *
 * <p>Throttled, and cached with a longer TTL since wiki content changes slowly.
 */
public class WikiClient
{
	private static final int MAX_EXTRACT_CHARS = 1200;

	private final HttpJson http;
	private final HttpUrl baseUrl;
	private final RateLimiter rateLimiter;
	private final TtlCache<String, WikiResult> cache;

	public WikiClient(HttpJson http, HttpUrl baseUrl, RateLimiter rateLimiter, TtlCache<String, WikiResult> cache)
	{
		this.http = http;
		this.baseUrl = baseUrl;
		this.rateLimiter = rateLimiter;
		this.cache = cache;
	}

	public WikiResult search(String query)
	{
		String key = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (key.isEmpty())
		{
			return WikiResult.notFound(query);
		}
		return cache.get(key, () -> doSearch(query.trim()));
	}

	private WikiResult doSearch(String query)
	{
		HttpUrl url = baseUrl.newBuilder()
			.addPathSegment("api.php")
			.addQueryParameter("action", "query")
			.addQueryParameter("format", "json")
			.addQueryParameter("redirects", "1")
			.addQueryParameter("prop", "extracts")
			.addQueryParameter("exintro", "1")
			.addQueryParameter("explaintext", "1")
			.addQueryParameter("generator", "search")
			.addQueryParameter("gsrsearch", query)
			.addQueryParameter("gsrlimit", "1")
			.build();

		JsonObject root = http.getObject(url, rateLimiter);
		JsonObject queryObj = Json.optObject(root, "query");
		JsonObject pages = queryObj == null ? null : Json.optObject(queryObj, "pages");
		if (pages == null)
		{
			return WikiResult.notFound(query);
		}

		for (java.util.Map.Entry<String, JsonElement> entry : pages.entrySet())
		{
			if (!entry.getValue().isJsonObject())
			{
				continue;
			}
			JsonObject page = entry.getValue().getAsJsonObject();
			String title = Json.optString(page, "title", null);
			String extract = Json.optString(page, "extract", "");
			if (title == null)
			{
				continue;
			}
			return new WikiResult(true, title, truncate(extract), articleUrl(title));
		}
		return WikiResult.notFound(query);
	}

	private String articleUrl(String title)
	{
		return baseUrl.newBuilder()
			.addPathSegment("w")
			.addPathSegment(title)
			.build()
			.toString();
	}

	private static String truncate(String extract)
	{
		if (extract == null)
		{
			return "";
		}
		String trimmed = extract.trim();
		return trimmed.length() > MAX_EXTRACT_CHARS ? trimmed.substring(0, MAX_EXTRACT_CHARS) + "..." : trimmed;
	}
}
