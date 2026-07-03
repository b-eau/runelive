package com.runelive.sidekick;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.runelive.sidekick.web.ChatDtos;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Full-stack end-to-end test: drive the running web server over HTTP through the entire pipeline —
 * fetch player context from (mock) WiseOldMan, run the (mock) Anthropic tool loop, call the live GE
 * price tool against (mock) prices, and return the answer — exactly as a browser would.
 */
public class SidekickE2EIntegrationTest
{
	private static final MediaType JSON = MediaType.get("application/json");

	private MockWebServer upstream;
	private Sidekick sidekick;
	private OkHttpClient client;
	private final Gson gson = new Gson();

	private final AtomicInteger anthropicCalls = new AtomicInteger();
	private final AtomicInteger womCalls = new AtomicInteger();

	private static final String PLAYER_JSON = "{"
		+ "\"displayName\":\"Zezima\",\"type\":\"regular\",\"build\":\"main\",\"combatLevel\":126,\"exp\":1,"
		+ "\"ehp\":1200.0,\"ehb\":300.0,"
		+ "\"latestSnapshot\":{\"data\":{\"skills\":{"
		+ "  \"overall\":{\"level\":2100,\"experience\":1},"
		+ "  \"slayer\":{\"level\":85,\"experience\":3300000,\"rank\":80}}}}}";

	private static final String MAPPING = "[{\"id\":4151,\"name\":\"Abyssal whip\"}]";
	private static final String LATEST = "{\"data\":{\"4151\":{\"high\":1650000,\"low\":1640000,\"highTime\":1,\"lowTime\":1}}}";

	private static final String TOOL_USE_RESPONSE = "{\"role\":\"assistant\",\"content\":["
		+ "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_grand_exchange_price\",\"input\":{\"item\":\"Abyssal whip\"}}],"
		+ "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}";

	private static final String END_TURN_RESPONSE = "{\"role\":\"assistant\",\"content\":["
		+ "{\"type\":\"text\",\"text\":\"An Abyssal whip is a great Slayer weapon and easily within budget. Grab one!\"}],"
		+ "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":150,\"output_tokens\":40}}";

	@Before
	public void setUp() throws Exception
	{
		upstream = new MockWebServer();
		upstream.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				String path = request.getPath();
				if (path.startsWith("/v1/messages"))
				{
					anthropicCalls.incrementAndGet();
					String body = request.getBody().readUtf8();
					// Second leg of the loop carries tool_result blocks -> end the turn.
					return json(body.contains("tool_result") ? END_TURN_RESPONSE : TOOL_USE_RESPONSE);
				}
				if (path.startsWith("/wom/players/"))
				{
					womCalls.incrementAndGet();
					return json(PLAYER_JSON);
				}
				if (path.startsWith("/prices/mapping"))
				{
					return json(MAPPING);
				}
				if (path.startsWith("/prices/latest"))
				{
					return json(LATEST);
				}
				return new MockResponse().setResponseCode(404);
			}
		});
		upstream.start();

		SidekickConfig config = SidekickConfig.builder()
			.anthropicApiKey("test-key")
			.anthropicBaseUrl(upstream.url("/"))
			.model("claude-opus-4-8")
			.thinking(false)
			.wiseOldManBaseUrl(upstream.url("/wom"))
			.pricesBaseUrl(upstream.url("/prices"))
			.wikiBaseUrl(upstream.url("/"))
			.port(0)
			.build();

		sidekick = new Sidekick(config);
		sidekick.start();
		client = new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build();
	}

	@After
	public void tearDown() throws Exception
	{
		if (sidekick != null)
		{
			sidekick.close();
		}
		upstream.shutdown();
	}

	@Test
	public void servesStaticUi() throws Exception
	{
		try (Response response = client.newCall(new Request.Builder().url(baseUrl() + "/").get().build()).execute())
		{
			assertEquals(200, response.code());
			String html = response.body().string();
			assertTrue(html.contains("OSRS Sidekick"));
		}
	}

	@Test
	public void healthReportsModel() throws Exception
	{
		try (Response response = client.newCall(new Request.Builder().url(baseUrl() + "/api/health").get().build()).execute())
		{
			assertEquals(200, response.code());
			assertTrue(response.body().string().contains("claude-opus-4-8"));
		}
	}

	@Test
	public void endToEndChatRunsToolLoopAndPersonalises() throws Exception
	{
		ChatDtos.ChatResponse response = chat("how much is an abyssal whip, should I buy one?", "Zezima");

		assertNull(response.error);
		assertTrue("final answer is the model's text", response.reply.contains("Abyssal whip"));

		// The agent actually priced the item via the live GE tool.
		assertEquals(1, response.tools.size());
		ChatDtos.ToolCallDto tool = response.tools.get(0);
		assertEquals("get_grand_exchange_price", tool.name);
		assertTrue("priced from mock GE data", tool.output.contains("1,645,000"));

		// Context was fetched and summarised for the UI.
		assertEquals("Zezima", response.context.username);
		assertEquals(126, response.context.combatLevel);

		// One tool-loop = two model calls; one player fetch.
		assertEquals(2, anthropicCalls.get());
		assertEquals(1, womCalls.get());
		assertTrue(response.usage.inputTokens > 0);
	}

	@Test
	public void playerContextIsCachedAcrossRequests() throws Exception
	{
		chat("first question about whips", "Zezima");
		chat("second question about whips", "Zezima");

		assertEquals("player context fetched once and reused across turns", 1, womCalls.get());
		assertEquals("two full tool loops", 4, anthropicCalls.get());
	}

	private ChatDtos.ChatResponse chat(String message, String player) throws Exception
	{
		String body = "{\"messages\":[{\"role\":\"user\",\"content\":" + gson.toJson(message) + "}],"
			+ "\"modality\":\"text\",\"player\":" + gson.toJson(player) + "}";
		Request request = new Request.Builder()
			.url(baseUrl() + "/api/chat")
			.post(RequestBody.create(body, JSON))
			.build();
		try (Response response = client.newCall(request).execute())
		{
			return gson.fromJson(response.body().string(), ChatDtos.ChatResponse.class);
		}
	}

	private String baseUrl()
	{
		return "http://localhost:" + sidekick.getWebServer().boundPort();
	}

	private static MockResponse json(String body)
	{
		return new MockResponse().setBody(body).setHeader("Content-Type", "application/json");
	}
}
