package com.runelive.sidekick.context.prices;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.http.HttpJson;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PriceClientTest
{
	private MockWebServer server;
	private PriceClient client;
	private final AtomicInteger mappingHits = new AtomicInteger();
	private final AtomicInteger latestHits = new AtomicInteger();

	private static final String MAPPING = "[{\"id\":4151,\"name\":\"Abyssal whip\"},{\"id\":1205,\"name\":\"Bronze dagger\"}]";
	private static final String LATEST = "{\"data\":{\"4151\":{\"high\":1650000,\"highTime\":1700000000,\"low\":1640000,\"lowTime\":1700000001}}}";

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest recordedRequest)
			{
				String path = recordedRequest.getPath();
				if (path.startsWith("/osrs/mapping"))
				{
					mappingHits.incrementAndGet();
					return json(MAPPING);
				}
				if (path.startsWith("/osrs/latest"))
				{
					latestHits.incrementAndGet();
					return json(LATEST);
				}
				return new MockResponse().setResponseCode(404);
			}
		});
		server.start();

		HttpJson http = new HttpJson(new OkHttpClient(), new Gson(), "test-agent");
		RateLimiter unlimited = new RateLimiter(1000, 1000, Duration.ofSeconds(1), Clock.systemUTC());
		client = new PriceClient(
			http, server.url("/osrs"), unlimited,
			new TtlCache<String, PriceClient.Mapping>(Duration.ofHours(6), Clock.systemUTC()),
			new TtlCache<String, Map<Integer, ItemPrice>>(Duration.ofMinutes(5), Clock.systemUTC()));
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	@Test
	public void resolvesPriceByNameCaseInsensitively()
	{
		ItemPrice price = client.priceByName("abyssal WHIP");
		assertEquals(4151, price.getId());
		assertEquals("Abyssal whip", price.getName());
		assertEquals(1650000, price.getHigh());
		assertEquals(1640000, price.getLow());
		assertEquals(1645000, price.midPrice());
	}

	@Test
	public void returnsZeroPriceForItemWithNoRecentTrades()
	{
		ItemPrice price = client.priceByName("Bronze dagger");
		assertEquals(1205, price.getId());
		assertEquals(0, price.midPrice());
	}

	@Test
	public void throwsForUnknownItem()
	{
		try
		{
			client.priceByName("Sword of a thousand truths");
			fail("expected ItemNotFoundException");
		}
		catch (ItemNotFoundException expected)
		{
			// expected
		}
	}

	@Test
	public void cachesMappingAndLatest()
	{
		client.priceByName("Abyssal whip");
		client.priceByName("Bronze dagger");
		assertEquals("mapping fetched once and reused", 1, mappingHits.get());
		assertEquals("latest fetched once and reused", 1, latestHits.get());
	}

	private static MockResponse json(String body)
	{
		return new MockResponse().setBody(body).setHeader("Content-Type", "application/json");
	}
}
