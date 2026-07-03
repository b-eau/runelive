package com.runelive.sidekick.context;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.context.wiseoldman.WiseOldManClient;
import com.runelive.sidekick.http.HttpJson;
import java.time.Clock;
import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CloudPlayerContextSourceTest
{
	private MockWebServer server;
	private CloudPlayerContextSource source;

	private static final String PLAYER_JSON = "{"
		+ "\"displayName\":\"Zezima\",\"type\":\"regular\",\"combatLevel\":126,\"exp\":1,"
		+ "\"latestSnapshot\":{\"data\":{\"skills\":{\"overall\":{\"level\":2100,\"experience\":1}}}}}";

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest recordedRequest)
			{
				return new MockResponse().setBody(PLAYER_JSON).setHeader("Content-Type", "application/json");
			}
		});
		server.start();

		HttpJson http = new HttpJson(new OkHttpClient(), new Gson(), "test-agent");
		RateLimiter unlimited = new RateLimiter(1000, 1000, Duration.ofSeconds(1), Clock.systemUTC());
		WiseOldManClient wom = new WiseOldManClient(http, server.url("/v2"), unlimited);
		source = new CloudPlayerContextSource(wom, new TtlCache<>(Duration.ofMinutes(5), Clock.systemUTC()));
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	@Test
	public void cachesAcrossCallsAndNormalisesUsername()
	{
		PlayerContext first = source.fetch("Zezima");
		PlayerContext second = source.fetch("zezima ");   // different case + trailing space
		PlayerContext third = source.fetch("  ZEZIMA");

		assertEquals("Zezima", first.getUsername());
		assertEquals("Zezima", second.getUsername());
		assertEquals("Zezima", third.getUsername());
		assertEquals("all three resolve to one upstream call thanks to caching + normalisation",
			1, server.getRequestCount());
	}
}
