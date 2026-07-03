package com.runelive.sidekick.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Null-safe accessors for navigating loosely-typed JSON responses from the community APIs. */
public final class Json
{
	private Json()
	{
	}

	public static String optString(JsonObject obj, String key, String fallback)
	{
		JsonElement e = obj == null ? null : obj.get(key);
		return e == null || e.isJsonNull() ? fallback : e.getAsString();
	}

	public static int optInt(JsonObject obj, String key, int fallback)
	{
		JsonElement e = obj == null ? null : obj.get(key);
		try
		{
			return e == null || e.isJsonNull() ? fallback : e.getAsInt();
		}
		catch (NumberFormatException ex)
		{
			return fallback;
		}
	}

	public static long optLong(JsonObject obj, String key, long fallback)
	{
		JsonElement e = obj == null ? null : obj.get(key);
		try
		{
			return e == null || e.isJsonNull() ? fallback : e.getAsLong();
		}
		catch (NumberFormatException ex)
		{
			return fallback;
		}
	}

	public static double optDouble(JsonObject obj, String key, double fallback)
	{
		JsonElement e = obj == null ? null : obj.get(key);
		try
		{
			return e == null || e.isJsonNull() ? fallback : e.getAsDouble();
		}
		catch (NumberFormatException ex)
		{
			return fallback;
		}
	}

	public static JsonObject optObject(JsonObject obj, String key)
	{
		JsonElement e = obj == null ? null : obj.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}
}
