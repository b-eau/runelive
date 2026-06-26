package com.runelive.sidekick.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.runelive.sidekick.cache.RateLimiter;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin JSON-over-HTTP helper shared by every upstream API client. Centralises the
 * cross-cutting concerns: rate limiting, the descriptive {@code User-Agent} the community APIs
 * ask for, response-code checking, and JSON parsing.
 *
 * <p>Calls run synchronously and are always invoked from background executor / web-server threads.
 * When this code is ported into the RuneLite plugin, these synchronous {@code execute()} calls
 * should move to {@code enqueue()} on the OkHttp pool with {@code clientThread.invoke()} callbacks.
 */
public final class HttpJson
{
	private final OkHttpClient http;
	private final Gson gson;
	private final String userAgent;

	public HttpJson(OkHttpClient http, Gson gson, String userAgent)
	{
		this.http = http;
		this.gson = gson;
		this.userAgent = userAgent;
	}

	public Gson gson()
	{
		return gson;
	}

	/** GET the URL (after acquiring a rate-limit token) and parse the body as a JSON object. */
	public JsonObject getObject(HttpUrl url, RateLimiter limiter)
	{
		JsonElement element = get(url, limiter);
		if (!element.isJsonObject())
		{
			throw new HttpException(0, "expected a JSON object from " + url);
		}
		return element.getAsJsonObject();
	}

	/** GET the URL (after acquiring a rate-limit token) and parse the body as arbitrary JSON. */
	public JsonElement get(HttpUrl url, RateLimiter limiter)
	{
		limiter.acquire();
		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", userAgent)
			.header("Accept", "application/json")
			.get()
			.build();
		return execute(request, url);
	}

	/** POST a JSON body and parse the JSON response. */
	public JsonObject postObject(HttpUrl url, JsonObject body, RateLimiter limiter)
	{
		limiter.acquire();
		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", userAgent)
			.header("Accept", "application/json")
			.post(RequestBody.create(gson.toJson(body), MediaTypes.JSON))
			.build();
		JsonElement element = execute(request, url);
		if (!element.isJsonObject())
		{
			throw new HttpException(0, "expected a JSON object from " + url);
		}
		return element.getAsJsonObject();
	}

	private JsonElement execute(Request request, HttpUrl url)
	{
		try (Response response = http.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				throw new HttpException(response.code(),
					"HTTP " + response.code() + " from " + url + ": " + truncate(body));
			}
			try
			{
				return JsonParser.parseString(body);
			}
			catch (JsonParseException e)
			{
				throw new HttpException(0, "invalid JSON from " + url, e);
			}
		}
		catch (IOException e)
		{
			throw new HttpException(0, "request to " + url + " failed: " + e.getMessage(), e);
		}
	}

	private static String truncate(String body)
	{
		return body.length() > 300 ? body.substring(0, 300) + "..." : body;
	}
}
