package com.osrssidekick;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin async HTTP client for the OSRS Sidekick backend. All calls run on the
 * OkHttp thread pool; callbacks are invoked off the client thread.
 */
@Slf4j
@Singleton
public class SidekickApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final SidekickSyncConfig config;

	@Inject
	SidekickApiClient(OkHttpClient okHttpClient, Gson gson, SidekickSyncConfig config)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.config = config;
	}

	@Value
	static class LinkStart
	{
		String code;
		String pollSecret;
		String linkUrl;
	}

	private HttpUrl baseUrl()
	{
		HttpUrl url = HttpUrl.parse(config.backendUrl());
		if (url == null)
		{
			throw new IllegalStateException("Invalid Sidekick server URL: " + config.backendUrl());
		}
		return url;
	}

	void startLink(long accountHash, String displayName, Consumer<LinkStart> onSuccess, Consumer<String> onError)
	{
		JsonObject body = new JsonObject();
		body.addProperty("accountHash", String.valueOf(accountHash));
		body.addProperty("displayName", displayName);

		Request request = new Request.Builder()
			.url(baseUrl().newBuilder().addPathSegments("api/link/start").build())
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("link start failed", e);
				onError.accept("Could not reach the Sidekick server");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						onError.accept("Sidekick server error (" + r.code() + ")");
						return;
					}
					JsonObject json = gson.fromJson(r.body().string(), JsonObject.class);
					onSuccess.accept(new LinkStart(
						json.get("code").getAsString(),
						json.get("pollSecret").getAsString(),
						json.get("linkUrl").getAsString()));
				}
			}
		});
	}

	/**
	 * Polls a pending link code. onResult receives the API token when claimed,
	 * null while still pending; onError fires on expiry or transport failure.
	 */
	void pollLink(String code, String pollSecret, Consumer<String> onResult, Consumer<String> onError)
	{
		HttpUrl url = baseUrl().newBuilder()
			.addPathSegments("api/link/poll")
			.addQueryParameter("code", code)
			.addQueryParameter("pollSecret", pollSecret)
			.build();

		okHttpClient.newCall(new Request.Builder().url(url).get().build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("link poll failed", e);
				onResult.accept(null); // transient; keep polling
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						onError.accept("Link code no longer valid");
						return;
					}
					JsonObject json = gson.fromJson(r.body().string(), JsonObject.class);
					String status = json.get("status").getAsString();
					switch (status)
					{
						case "CLAIMED":
							onResult.accept(json.get("apiToken").getAsString());
							break;
						case "PENDING":
							onResult.accept(null);
							break;
						default: // EXPIRED / CONSUMED / UNKNOWN
							onError.accept("Link code expired — try again");
							break;
					}
				}
			}
		});
	}

	void ingest(String apiToken, String profileKind, String accountType, String displayName,
		List<JsonObject> events, Runnable onSuccess, Consumer<String> onError)
	{
		JsonObject body = new JsonObject();
		body.addProperty("profileKind", profileKind);
		if (accountType != null)
		{
			body.addProperty("accountType", accountType);
		}
		if (displayName != null)
		{
			body.addProperty("displayName", displayName);
		}
		body.add("events", gson.toJsonTree(events));

		Request request = new Request.Builder()
			.url(baseUrl().newBuilder().addPathSegments("api/ingest").build())
			.header("Authorization", "Bearer " + apiToken)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("ingest failed", e);
				onError.accept("network");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						onSuccess.run();
					}
					else
					{
						log.debug("ingest rejected: {}", r.code());
						onError.accept(r.code() == 401 ? "unauthorized" : "server");
					}
				}
			}
		});
	}
}
