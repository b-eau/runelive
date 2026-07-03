package com.runelive.sidekick.context.prices;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.http.HttpJson;
import com.runelive.sidekick.http.Json;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import okhttp3.HttpUrl;

/**
 * Client for the <a href="https://prices.runescape.wiki/">OSRS Wiki real-time prices API</a>.
 *
 * <p>Two endpoints are used: {@code /mapping} (the item id ⇄ name table, rarely changes — cached
 * for a long TTL) and {@code /latest} (current buy/sell prices for every item — cached for a short
 * TTL). Both are throttled. This powers the agent's {@code get_grand_exchange_price} tool so its
 * cost recommendations use live prices instead of fabricated numbers.
 */
public class PriceClient
{
	private static final String MAPPING_KEY = "mapping";
	private static final String LATEST_KEY = "latest";

	private final HttpJson http;
	private final HttpUrl baseUrl;
	private final RateLimiter rateLimiter;
	private final TtlCache<String, Mapping> mappingCache;
	private final TtlCache<String, Map<Integer, ItemPrice>> latestCache;

	public PriceClient(
		HttpJson http,
		HttpUrl baseUrl,
		RateLimiter rateLimiter,
		TtlCache<String, Mapping> mappingCache,
		TtlCache<String, Map<Integer, ItemPrice>> latestCache)
	{
		this.http = http;
		this.baseUrl = baseUrl;
		this.rateLimiter = rateLimiter;
		this.mappingCache = mappingCache;
		this.latestCache = latestCache;
	}

	/**
	 * Resolves an item by (case-insensitive) name and returns its current price.
	 *
	 * @throws ItemNotFoundException if no item matches the name
	 */
	public ItemPrice priceByName(String itemName)
	{
		Mapping mapping = mappingCache.get(MAPPING_KEY, this::loadMapping);
		String key = itemName == null ? "" : itemName.trim().toLowerCase(Locale.ROOT);
		Integer id = mapping.nameToId.get(key);
		if (id == null)
		{
			throw new ItemNotFoundException(itemName);
		}
		return priceById(id, mapping);
	}

	private ItemPrice priceById(int id, Mapping mapping)
	{
		Map<Integer, ItemPrice> latest = latestCache.get(LATEST_KEY, this::loadLatest);
		ItemPrice price = latest.get(id);
		String name = mapping.idToName.getOrDefault(id, String.valueOf(id));
		if (price == null)
		{
			// Known item, but no recent trades — report a zero price rather than failing.
			return new ItemPrice(id, name, 0, 0, 0, 0);
		}
		return new ItemPrice(id, name, price.getHigh(), price.getLow(), price.getHighTime(), price.getLowTime());
	}

	private Mapping loadMapping()
	{
		HttpUrl url = baseUrl.newBuilder().addPathSegment("mapping").build();
		JsonElement element = http.get(url, rateLimiter);
		if (!element.isJsonArray())
		{
			throw new IllegalStateException("expected a JSON array from the prices mapping endpoint");
		}
		JsonArray array = element.getAsJsonArray();
		Map<String, Integer> nameToId = new HashMap<>();
		Map<Integer, String> idToName = new HashMap<>();
		for (JsonElement e : array)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			int id = Json.optInt(o, "id", -1);
			String name = Json.optString(o, "name", null);
			if (id < 0 || name == null)
			{
				continue;
			}
			nameToId.put(name.toLowerCase(Locale.ROOT), id);
			idToName.put(id, name);
		}
		return new Mapping(nameToId, idToName);
	}

	private Map<Integer, ItemPrice> loadLatest()
	{
		HttpUrl url = baseUrl.newBuilder().addPathSegment("latest").build();
		JsonObject root = http.getObject(url, rateLimiter);
		JsonObject data = Json.optObject(root, "data");
		Map<Integer, ItemPrice> prices = new HashMap<>();
		if (data == null)
		{
			return prices;
		}
		for (Map.Entry<String, JsonElement> entry : data.entrySet())
		{
			int id;
			try
			{
				id = Integer.parseInt(entry.getKey());
			}
			catch (NumberFormatException ex)
			{
				continue;
			}
			if (!entry.getValue().isJsonObject())
			{
				continue;
			}
			JsonObject p = entry.getValue().getAsJsonObject();
			prices.put(id, new ItemPrice(
				id,
				String.valueOf(id),
				Json.optInt(p, "high", 0),
				Json.optInt(p, "low", 0),
				Json.optLong(p, "highTime", 0),
				Json.optLong(p, "lowTime", 0)));
		}
		return prices;
	}

	/** Immutable item id ⇄ name lookup table. */
	public static final class Mapping
	{
		final Map<String, Integer> nameToId;
		final Map<Integer, String> idToName;

		Mapping(Map<String, Integer> nameToId, Map<Integer, String> idToName)
		{
			this.nameToId = nameToId;
			this.idToName = idToName;
		}
	}
}
