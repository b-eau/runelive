package com.runelive.sidekick;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.runelive.sidekick.agent.AgentService;
import com.runelive.sidekick.agent.ToolRegistry;
import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.agent.tools.GrandExchangePriceTool;
import com.runelive.sidekick.agent.tools.WikiSearchTool;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.context.client.ClientPlayerContextSource;
import com.runelive.sidekick.context.prices.ItemPrice;
import com.runelive.sidekick.context.prices.PriceClient;
import com.runelive.sidekick.context.wiki.WikiClient;
import com.runelive.sidekick.context.wiki.WikiResult;
import com.runelive.sidekick.http.HttpJson;
import com.runelive.sidekick.llm.AnthropicClient;
import com.runelive.sidekick.llm.GeminiClient;
import com.runelive.sidekick.llm.LlmClient;
import com.runelive.sidekick.llm.LlmProvider;
import com.runelive.sidekick.web.ChatService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * The RuneLite plugin that wires the OSRS Sidekick into the live client.
 *
 * <p>It mirrors the structure of {@link com.runelive.sidekick.Sidekick} (the web devtool
 * composition root), swapping {@code CloudPlayerContextSource} → {@link ClientPlayerContextSource}
 * and dropping the web server. Everything else — caches, rate limiters, LLM client, agent loop —
 * is identical and re-used verbatim.
 *
 * <p>The plugin is disabled by default. When enabled, it contacts the configured AI provider's
 * servers using the player's API key.
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS Sidekick",
	configName = "osrs-sidekick",
	description = "Personalised AI chat sidekick — talks to an external AI provider to give advice tailored to your account",
	tags = {"ai", "chat", "assistant", "advice", "helper"},
	enabledByDefault = false)
public class SidekickPlugin extends Plugin
{
	// Cache TTLs — identical to the web devtool for consistency.
	private static final Duration PRICE_LATEST_TTL = Duration.ofMinutes(5);
	private static final Duration PRICE_MAPPING_TTL = Duration.ofHours(6);
	private static final Duration WIKI_TTL = Duration.ofHours(1);
	private static final int MAX_AGENT_STEPS = 6;

	@Inject
	private EventBus eventBus;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private SidekickPluginConfig config;

	@Inject
	private ClientPlayerContextSource contextSource;

	@Getter
	private ChatService chatService;

	private PriceClient priceClient;
	private WikiClient wikiClient;

	@Override
	protected void startUp()
	{
		if (!config.enableSidekick())
		{
			log.debug("OSRS Sidekick is disabled in config; not starting");
			return;
		}
		String apiKey = config.apiKey();
		if (apiKey == null || apiKey.trim().isEmpty())
		{
			log.warn("OSRS Sidekick: no API key configured — plugin will not start");
			return;
		}

		Clock clock = Clock.systemUTC();
		HttpJson httpJson = new HttpJson(okHttpClient, gson, "osrs-sidekick-plugin/1.0");

		// Grand Exchange prices
		TtlCache<String, PriceClient.Mapping> mappingCache = new TtlCache<>(PRICE_MAPPING_TTL, clock);
		TtlCache<String, Map<Integer, ItemPrice>> latestCache = new TtlCache<>(PRICE_LATEST_TTL, clock);
		priceClient = new PriceClient(
			httpJson,
			HttpUrl.get("https://prices.runescape.wiki/api/v1/osrs"),
			new RateLimiter(10, 1, Duration.ofSeconds(2), clock),
			mappingCache,
			latestCache);

		// Wiki
		wikiClient = new WikiClient(
			httpJson,
			HttpUrl.get("https://oldschool.runescape.wiki"),
			new RateLimiter(5, 1, Duration.ofSeconds(2), clock),
			new TtlCache<>(WIKI_TTL, clock));

		// LLM
		LlmClient llm = buildLlmClient(config, okHttpClient, gson);

		// Agent
		List<AgentTool> tools = List.of(
			new GrandExchangePriceTool(priceClient),
			new WikiSearchTool(wikiClient));
		AgentService agentService = new AgentService(llm, new ToolRegistry(tools), MAX_AGENT_STEPS);

		// Context: live client data
		chatService = new ChatService(contextSource, agentService, null);

		// Register for game events so the snapshot stays current
		eventBus.register(contextSource);

		log.info("OSRS Sidekick started (provider: {}, model: {})", config.provider(), config.model());
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(contextSource);
		chatService = null;
		priceClient = null;
		wikiClient = null;
		log.info("OSRS Sidekick stopped");
	}

	@Provides
	SidekickPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SidekickPluginConfig.class);
	}

	private static LlmClient buildLlmClient(SidekickPluginConfig config, OkHttpClient http, Gson gson)
	{
		LlmProvider provider = LlmProvider.fromString(config.provider());
		String model = config.model();
		long maxTokens = config.maxTokens();

		if (provider == LlmProvider.GEMINI)
		{
			if (model == null || model.isEmpty())
			{
				model = "gemini-3.5-flash";
			}
			return GeminiClient.builder()
				.http(http)
				.gson(gson)
				.baseUrl(HttpUrl.get("https://generativelanguage.googleapis.com"))
				.apiKey(config.apiKey())
				.model(model)
				.maxTokens(maxTokens)
				.build();
		}

		if (model == null || model.isEmpty())
		{
			model = "claude-opus-4-8";
		}
		return AnthropicClient.builder()
			.http(http)
			.gson(gson)
			.baseUrl(HttpUrl.get("https://api.anthropic.com"))
			.apiKey(config.apiKey())
			.model(model)
			.maxTokens(maxTokens)
			.thinking(false)
			.userAgent("osrs-sidekick-plugin/1.0")
			.build();
	}
}
