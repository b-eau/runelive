package com.runelive.sidekick.context.wiseoldman;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.http.HttpJson;
import java.time.Clock;
import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WiseOldManClientTest
{
	private MockWebServer server;
	private WiseOldManClient client;

	private static final String PLAYER_JSON = "{"
		+ "\"username\":\"zezima\",\"displayName\":\"Zezima\",\"type\":\"ironman\",\"build\":\"main\","
		+ "\"combatLevel\":126,\"exp\":400000000,\"ehp\":1200.5,\"ehb\":300.2,"
		+ "\"registeredAt\":\"2015-01-01T00:00:00.000Z\",\"lastChangedAt\":\"2024-06-01T00:00:00.000Z\","
		+ "\"latestSnapshot\":{\"data\":{"
		+ "  \"skills\":{"
		+ "    \"overall\":{\"rank\":1,\"level\":2100,\"experience\":400000000},"
		+ "    \"attack\":{\"rank\":50,\"level\":99,\"experience\":13034431},"
		+ "    \"slayer\":{\"rank\":80,\"level\":85,\"experience\":3300000}},"
		+ "  \"bosses\":{"
		+ "    \"zulrah\":{\"rank\":1000,\"kills\":1500},"
		+ "    \"vorkath\":{\"rank\":-1,\"kills\":-1}},"
		+ "  \"activities\":{"
		+ "    \"clue_scrolls_all\":{\"rank\":500,\"score\":250},"
		+ "    \"last_man_standing\":{\"rank\":-1,\"score\":-1}}"
		+ "}}}";

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		HttpJson http = new HttpJson(new OkHttpClient(), new Gson(), "test-agent");
		RateLimiter unlimited = new RateLimiter(1000, 1000, Duration.ofSeconds(1), Clock.systemUTC());
		client = new WiseOldManClient(http, server.url("/v2"), unlimited);
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	@Test
	public void mapsPlayerDocument() throws Exception
	{
		server.enqueue(new MockResponse().setBody(PLAYER_JSON).setHeader("Content-Type", "application/json"));

		PlayerContext ctx = client.fetchPlayer("zezima");

		RecordedRequest request = server.takeRequest();
		assertEquals("/v2/players/zezima", request.getPath());

		assertEquals("Zezima", ctx.getUsername());
		assertEquals("ironman", ctx.getAccountType());
		assertTrue(ctx.isIronman());
		assertEquals("main", ctx.getBuild());
		assertEquals(126, ctx.getCombatLevel());
		assertEquals(2100, ctx.getTotalLevel());
		assertEquals(400000000L, ctx.getTotalExperience());
		assertEquals(1200.5, ctx.getEfficientHoursPlayed(), 0.001);
		assertEquals(300.2, ctx.getEfficientHoursBossed(), 0.001);
		assertEquals("2015-01-01T00:00:00.000Z", ctx.getRegisteredAt());

		assertEquals(99, ctx.skillLevel("attack"));
		assertEquals(85, ctx.skillLevel("slayer"));
		assertNull("overall is consumed into totalLevel, not a skill", ctx.skill("overall"));

		assertEquals(1500, ctx.bossKills("zulrah"));
		assertFalse("unranked bosses (kills <= 0) are dropped", ctx.getBosses().containsKey("vorkath"));

		assertTrue(ctx.getActivities().containsKey("clue_scrolls_all"));
		assertEquals(250, ctx.getActivities().get("clue_scrolls_all").getScore());
		assertFalse("unranked activities are dropped", ctx.getActivities().containsKey("last_man_standing"));
	}

	@Test
	public void throwsPlayerNotFoundOn404()
	{
		server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"Player not found.\"}"));
		try
		{
			client.fetchPlayer("doesnotexist");
			fail("expected PlayerNotFoundException");
		}
		catch (PlayerNotFoundException expected)
		{
			assertTrue(expected.getMessage().contains("doesnotexist"));
		}
	}
}
