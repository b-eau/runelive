package com.runelive.sidekick.context.wiki;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.http.HttpJson;
import java.time.Clock;
import java.time.Duration;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WikiClientTest
{
	private MockWebServer server;
	private WikiClient client;

	private static final String FOUND = "{\"query\":{\"pages\":{\"12345\":{\"pageid\":12345,"
		+ "\"title\":\"Abyssal whip\",\"extract\":\"The abyssal whip is a one-handed Melee weapon.\"}}}}";
	private static final String MISSING = "{\"batchcomplete\":\"\"}";

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest recordedRequest)
			{
				HttpUrl url = recordedRequest.getRequestUrl();
				String q = url == null ? "" : String.valueOf(url.queryParameter("gsrsearch"));
				String body = q.contains("missing") ? MISSING : FOUND;
				return new MockResponse().setBody(body).setHeader("Content-Type", "application/json");
			}
		});
		server.start();

		HttpJson http = new HttpJson(new OkHttpClient(), new Gson(), "test-agent");
		RateLimiter unlimited = new RateLimiter(1000, 1000, Duration.ofSeconds(1), Clock.systemUTC());
		client = new WikiClient(http, server.url("/"), unlimited,
			new TtlCache<>(Duration.ofHours(1), Clock.systemUTC()));
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	@Test
	public void returnsArticleSummary()
	{
		WikiResult result = client.search("abyssal whip");
		assertTrue(result.isFound());
		assertEquals("Abyssal whip", result.getTitle());
		assertTrue(result.getExtract().contains("one-handed Melee weapon"));
		assertTrue(result.getUrl().contains("/w/Abyssal"));
	}

	@Test
	public void reportsNotFound()
	{
		WikiResult result = client.search("missing thing");
		assertFalse(result.isFound());
	}

	@Test
	public void cachesRepeatedQueries()
	{
		client.search("abyssal whip");
		client.search("Abyssal Whip");  // same query, different case
		assertEquals("cached, so only one upstream request", 1, server.getRequestCount());
	}
}
