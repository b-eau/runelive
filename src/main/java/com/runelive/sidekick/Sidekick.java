package com.runelive.sidekick;

import com.google.gson.Gson;
import com.runelive.sidekick.agent.AgentService;
import com.runelive.sidekick.agent.ToolRegistry;
import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.agent.tools.GrandExchangePriceTool;
import com.runelive.sidekick.agent.tools.WikiSearchTool;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.context.CloudPlayerContextSource;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerContextSource;
import com.runelive.sidekick.context.prices.ItemPrice;
import com.runelive.sidekick.context.prices.PriceClient;
import com.runelive.sidekick.context.wiki.WikiClient;
import com.runelive.sidekick.context.wiki.WikiResult;
import com.runelive.sidekick.context.wiseoldman.WiseOldManClient;
import com.runelive.sidekick.http.HttpJson;
import com.runelive.sidekick.llm.AnthropicClient;
import com.runelive.sidekick.web.ChatService;
import com.runelive.sidekick.web.WebServer;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import okhttp3.OkHttpClient;

/**
 * Composition root: wires the whole sidekick from a {@link SidekickConfig} using plain constructor
 * injection (no DI container, to keep the dependency footprint small). Constructor injection maps
 * cleanly onto {@code @Inject} when this is restructured into a RuneLite plugin — the assembly here
 * is what {@code startUp()} would do, swapping the cloud {@link PlayerContextSource} for a
 * client-backed one and dropping the {@link WebServer}.
 */
public class Sidekick implements AutoCloseable
{
	// Cache TTLs and rate limits are internal tuning, kept generous to be a good API citizen.
	private static final Duration PLAYER_TTL = Duration.ofMinutes(5);
	private static final Duration PRICE_LATEST_TTL = Duration.ofMinutes(5);
	private static final Duration PRICE_MAPPING_TTL = Duration.ofHours(6);
	private static final Duration WIKI_TTL = Duration.ofHours(1);

	private final OkHttpClient http;
	private final ExecutorService executor;

	@Getter
	private final PlayerContextSource playerContextSource;
	@Getter
	private final AgentService agentService;
	@Getter
	private final ChatService chatService;
	@Getter
	private final WebServer webServer;
	@Getter
	private final PriceClient priceClient;
	@Getter
	private final WikiClient wikiClient;

	public Sidekick(SidekickConfig config)
	{
		this(config, Clock.systemUTC());
	}

	public Sidekick(SidekickConfig config, Clock clock)
	{
		this.http = new OkHttpClient.Builder()
			.connectTimeout(Duration.ofSeconds(20))
			.readTimeout(Duration.ofSeconds(120))
			.writeTimeout(Duration.ofSeconds(30))
			.build();
		this.executor = Executors.newCachedThreadPool(named("sidekick-web"));

		Gson gson = new Gson();
		HttpJson httpJson = new HttpJson(http, gson, config.getUserAgent());

		// Player context (WiseOldMan), cached per-RSN and throttled.
		WiseOldManClient wiseOldMan = new WiseOldManClient(
			httpJson, config.getWiseOldManBaseUrl(),
			new RateLimiter(5, 1, Duration.ofSeconds(2), clock));
		TtlCache<String, PlayerContext> playerCache = new TtlCache<>(PLAYER_TTL, clock);
		this.playerContextSource = new CloudPlayerContextSource(wiseOldMan, playerCache);

		// Grand Exchange prices (OSRS Wiki), with a short-lived latest cache and a long mapping cache.
		TtlCache<String, PriceClient.Mapping> mappingCache = new TtlCache<>(PRICE_MAPPING_TTL, clock);
		TtlCache<String, Map<Integer, ItemPrice>> latestCache = new TtlCache<>(PRICE_LATEST_TTL, clock);
		this.priceClient = new PriceClient(
			httpJson, config.getPricesBaseUrl(),
			new RateLimiter(10, 1, Duration.ofSeconds(2), clock),
			mappingCache,
			latestCache);

		// Wiki knowledge, cached longer.
		this.wikiClient = new WikiClient(
			httpJson, config.getWikiBaseUrl(),
			new RateLimiter(5, 1, Duration.ofSeconds(2), clock),
			new TtlCache<String, WikiResult>(WIKI_TTL, clock));

		// The brain.
		AnthropicClient llm = AnthropicClient.builder()
			.http(http)
			.gson(gson)
			.baseUrl(config.getAnthropicBaseUrl())
			.apiKey(config.getAnthropicApiKey())
			.model(config.getModel())
			.maxTokens(config.getMaxTokens())
			.thinking(config.isThinking())
			.userAgent(config.getUserAgent())
			.build();

		List<AgentTool> tools = List.of(
			new GrandExchangePriceTool(priceClient),
			new WikiSearchTool(wikiClient));
		ToolRegistry registry = new ToolRegistry(tools);
		this.agentService = new AgentService(llm, registry, config.getMaxAgentSteps());

		this.chatService = new ChatService(playerContextSource, agentService, config.getDefaultPlayer());
		this.webServer = new WebServer(
			config.getPort(), executor, gson, chatService, config.getModel(), config.getDefaultPlayer());
	}

	public void start() throws IOException
	{
		webServer.start();
	}

	@Override
	public void close()
	{
		webServer.stop();
		executor.shutdownNow();
		http.dispatcher().executorService().shutdownNow();
		http.connectionPool().evictAll();
	}

	private static ThreadFactory named(String prefix)
	{
		AtomicInteger counter = new AtomicInteger();
		return runnable ->
		{
			Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
