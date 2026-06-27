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
import com.runelive.sidekick.http.HttpJson;
import com.runelive.sidekick.llm.AnthropicClient;
import com.runelive.sidekick.llm.GeminiClient;
import com.runelive.sidekick.llm.LlmClient;
import com.runelive.sidekick.llm.LlmProvider;
import com.runelive.sidekick.voice.GeminiVoiceClient;
import com.runelive.sidekick.voice.VoiceService;
import com.runelive.sidekick.web.ChatService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.HotkeyListener;
import java.util.concurrent.TimeUnit;
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
 * servers using the player's API key. Services are (re)started whenever the config changes so the
 * user does not need to toggle the plugin off and on after adjusting settings.
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS Sidekick",
	configName = "osrs-sidekick",
	description = "Personalised AI chat sidekick — talks to an external AI provider to give advice tailored to your account. Requires an API key for the chosen provider.",
	tags = {"ai", "chat", "assistant", "advice", "helper"},
	enabledByDefault = false)
public class SidekickPlugin extends Plugin
{
	// Cache TTLs — identical to the web devtool for consistency.
	private static final Duration PRICE_LATEST_TTL = Duration.ofMinutes(5);
	private static final Duration PRICE_MAPPING_TTL = Duration.ofHours(6);
	private static final Duration WIKI_TTL = Duration.ofHours(1);
	private static final int MAX_AGENT_STEPS = 10;

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

	@Inject
	private KeyManager keyManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Getter
	private ChatService chatService;

	private PriceClient priceClient;
	private WikiClient wikiClient;
	private VoiceService voiceService;
	private HotkeyListener hotkeyListener;

	@Override
	protected void startUp()
	{
		eventBus.register(contextSource);
		startServices();
	}

	@Override
	protected void shutDown()
	{
		stopServices();
		eventBus.unregister(contextSource);
	}

	/** Re-applies config without requiring the user to disable and re-enable the plugin. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"osrs-sidekick".equals(event.getGroup()))
		{
			return;
		}
		stopServices();
		startServices();
	}

	@Provides
	SidekickPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SidekickPluginConfig.class);
	}

	// ── Private ──────────────────────────────────────────────────────────────────────────────────

	private void startServices()
	{
		String apiKey = config.apiKey();
		if (apiKey == null || apiKey.trim().isEmpty())
		{
			log.info("OSRS Sidekick: no API key set — configure one in the plugin settings");
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

		// Voice (optional — requires Gemini key regardless of main LLM provider)
		if (config.enableVoice())
		{
			String voiceKey = resolveVoiceApiKey(config);
			if (voiceKey != null)
			{
				// TTS responses are large; RuneLite's default timeout is too short.
			OkHttpClient voiceHttpClient = okHttpClient.newBuilder()
				.readTimeout(60, TimeUnit.SECONDS)
				.build();
			GeminiVoiceClient voiceClient = new GeminiVoiceClient(
					voiceHttpClient, gson,
					HttpUrl.get("https://generativelanguage.googleapis.com"),
					voiceKey,
					config.ttsVoice());
				voiceService = new VoiceService(
					voiceClient, agentService, contextSource, chatMessageManager, config.enableTts());
				hotkeyListener = new HotkeyListener(() -> config.voiceHotkey())
				{
					@Override
					public void hotkeyPressed()
					{
						voiceService.startRecording();
					}

					@Override
					public void hotkeyReleased()
					{
						voiceService.stopAndProcess();
					}
				};
				keyManager.registerKeyListener(hotkeyListener);
				log.info("OSRS Sidekick voice activated (tts={})", config.enableTts());
			}
			else
			{
				log.warn("OSRS Sidekick: voice enabled but no Gemini API key available — voice disabled");
			}
		}

		log.info("OSRS Sidekick started (provider={}, model={})", config.provider(), config.model());
	}

	private void stopServices()
	{
		if (hotkeyListener != null)
		{
			keyManager.unregisterKeyListener(hotkeyListener);
			hotkeyListener = null;
		}
		if (voiceService != null)
		{
			voiceService.shutdown();
			voiceService = null;
		}
		chatService = null;
		priceClient = null;
		wikiClient = null;
	}

	private static String resolveVoiceApiKey(SidekickPluginConfig config)
	{
		String voiceKey = config.voiceApiKey();
		if (voiceKey != null && !voiceKey.trim().isEmpty())
		{
			return voiceKey.trim();
		}
		if (LlmProvider.fromString(config.provider()) == LlmProvider.GEMINI)
		{
			String mainKey = config.apiKey();
			return (mainKey != null && !mainKey.trim().isEmpty()) ? mainKey.trim() : null;
		}
		return null;
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
				model = "gemini-2.5-flash";
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
