package com.runelive.sidekick;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.runelive.sidekick.agent.AgentReply;
import com.runelive.sidekick.agent.AgentService;
import com.runelive.sidekick.agent.ToolRegistry;
import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.agent.tools.GrandExchangePriceTool;
import com.runelive.sidekick.agent.tools.WikiSearchTool;
import com.runelive.sidekick.cache.RateLimiter;
import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.context.CloudPlayerContextSource;
import com.runelive.sidekick.context.client.ClientPlayerContextSource;
import com.runelive.sidekick.context.prices.ItemPrice;
import com.runelive.sidekick.context.prices.PriceClient;
import com.runelive.sidekick.context.wiki.WikiClient;
import com.runelive.sidekick.context.wiseoldman.WiseOldManClient;
import com.runelive.sidekick.conversation.Conversation;
import com.runelive.sidekick.conversation.ConversationManager;
import com.runelive.sidekick.conversation.ConversationStore;
import com.runelive.sidekick.conversation.RecallConversationsTool;
import com.runelive.sidekick.http.HttpJson;
import com.runelive.sidekick.llm.AnthropicClient;
import com.runelive.sidekick.llm.GeminiClient;
import com.runelive.sidekick.llm.LlmClient;
import com.runelive.sidekick.llm.LlmMessage;
import com.runelive.sidekick.llm.LlmProvider;
import com.runelive.sidekick.llm.XaiClient;
import com.runelive.sidekick.tools.HiscoresLookupTool;
import com.runelive.sidekick.voice.GeminiVoiceClient;
import com.runelive.sidekick.voice.VoiceBackend;
import com.runelive.sidekick.voice.VoiceService;
import com.runelive.sidekick.voice.realtime.OpenAiRealtimeSession;
import com.runelive.sidekick.voice.realtime.RealtimeVoiceBackend;
import com.runelive.sidekick.web.ChatService;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * The RuneLite plugin that wires the OSRS Sidekick into the live client.
 *
 * <p>It mirrors the structure of {@link com.runelive.sidekick.Sidekick} (the web devtool
 * composition root), swapping {@code CloudPlayerContextSource} → {@link ClientPlayerContextSource}
 * and dropping the web server. The portable core — caches, rate limiters, LLM client, agent loop —
 * is re-used verbatim.
 *
 * <p>Questions reach the agent three ways: the {@code ::sk} chat command, push-to-talk voice, and
 * the sidebar panel's follow-up box. All three funnel through {@link #submitQuery(String)}, which
 * appends to the active {@link Conversation}, runs the agent with the running history plus a memory
 * block of past conversations, and re-renders the panel.
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
public class SidekickPlugin extends Plugin implements SidekickPanel.Listener
{
	// Cache TTLs — identical to the web devtool for consistency.
	private static final Duration PRICE_LATEST_TTL = Duration.ofMinutes(5);
	private static final Duration PRICE_MAPPING_TTL = Duration.ofHours(6);
	private static final Duration WIKI_TTL = Duration.ofHours(1);
	private static final Duration HISCORES_TTL = Duration.ofMinutes(10);
	private static final int MAX_AGENT_STEPS = 50;

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

	@Inject
	private ClientToolbar clientToolbar;

	@Getter
	private ChatService chatService;

	private AgentService agentService;
	private ExecutorService queryExecutor;
	private SidekickPanel sidekickPanel;
	private NavigationButton navButton;
	private PriceClient priceClient;
	private WikiClient wikiClient;
	private VoiceBackend voiceBackend;
	private HotkeyListener hotkeyListener;

	// Conversation history lives independently of the API key, so it survives config changes and is
	// browsable even before a key is configured.
	private ConversationStore conversationStore;
	private ConversationManager conversationManager;

	@Override
	protected void startUp()
	{
		eventBus.register(contextSource);

		conversationStore = new ConversationStore(gson);
		conversationManager = new ConversationManager(conversationStore);

		sidekickPanel = new SidekickPanel();
		sidekickPanel.setListener(this);
		navButton = NavigationButton.builder()
			.tooltip("OSRS Sidekick")
			.icon(buildIcon())
			.priority(5)
			.panel(sidekickPanel)
			.build();
		clientToolbar.addNavigation(navButton);

		queryExecutor = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "sidekick-query");
			t.setDaemon(true);
			return t;
		});

		startServices();
	}

	@Override
	protected void shutDown()
	{
		if (queryExecutor != null)
		{
			queryExecutor.shutdownNow();
			queryExecutor = null;
		}
		stopServices();
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		sidekickPanel = null;
		conversationManager = null;
		conversationStore = null;
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

	// ── Panel listener ───────────────────────────────────────────────────────────────────────────

	/** A follow-up typed into the panel. The panel has already shown the pending state. */
	@Override
	public void onSend(String text)
	{
		submitQuery(text.trim());
	}

	@Override
	public void onNewConversation()
	{
		ConversationManager manager = conversationManager;
		if (manager != null)
		{
			manager.startNew();
		}
	}

	@Override
	public void onHistoryRequested()
	{
		ExecutorService exec = queryExecutor;
		ConversationManager manager = conversationManager;
		if (exec == null || manager == null)
		{
			return;
		}
		exec.submit(() ->
		{
			String username = currentUsername();
			List<Conversation> recent = username == null ? List.of() : manager.recent(username);
			SidekickPanel panel = sidekickPanel;
			if (panel != null)
			{
				panel.showHistory(recent);
			}
		});
	}

	@Override
	public void onConversationSelected(String id)
	{
		ExecutorService exec = queryExecutor;
		ConversationManager manager = conversationManager;
		if (exec == null || manager == null)
		{
			return;
		}
		exec.submit(() ->
		{
			String username = currentUsername();
			Conversation conversation = username == null ? null : manager.load(username, id);
			if (conversation == null)
			{
				return;
			}
			manager.setActive(conversation);
			SidekickPanel panel = sidekickPanel;
			if (panel != null)
			{
				panel.showConversation(conversation);
			}
		});
	}

	// ── Query entry points ───────────────────────────────────────────────────────────────────────

	/**
	 * Handles the {@code ::sk <question>} client command. {@code ::}-prefixed input is intercepted by
	 * the client and delivered as a {@link CommandExecuted} event (never a chat message), so this —
	 * not {@code ChatCommandManager} (which only sees the {@code !bang} commands a player sends to
	 * chat) — is the correct hook.
	 */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"sk".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		String[] args = event.getArguments();
		String query = args == null ? "" : String.join(" ", args).trim();
		if (query.isEmpty())
		{
			postSystemMessage("Usage: <col=ffffff>::sk your question</col>");
			return;
		}
		ask(query);
	}

	/** External trigger (chat command / voice): show the pending state, focus the panel, then run. */
	private void ask(String query)
	{
		if (query.isEmpty())
		{
			return;
		}
		if (agentService == null)
		{
			postSystemMessage("<col=ff0000>Sidekick not configured</col> — add an API key in the plugin settings.");
			return;
		}
		SidekickPanel panel = sidekickPanel;
		if (panel != null)
		{
			panel.showPending(query);
		}
		openPanel();
		submitQuery(query);
	}

	/** The shared worker: append the turn, run the agent with history + memory, re-render the panel. */
	private void submitQuery(String query)
	{
		if (query.isEmpty())
		{
			return;
		}
		AgentService svc = agentService;
		ExecutorService exec = queryExecutor;
		ConversationManager manager = conversationManager;
		if (svc == null || exec == null || manager == null)
		{
			SidekickPanel panel = sidekickPanel;
			if (panel != null)
			{
				panel.showError("Sidekick isn't configured — add an API key in the plugin settings.");
			}
			return;
		}
		exec.submit(() ->
		{
			try
			{
				PlayerContext context;
				try
				{
					context = contextSource.fetch(null);
				}
				catch (PlayerNotFoundException e)
				{
					SidekickPanel panel = sidekickPanel;
					if (panel != null)
					{
						panel.showError("No player logged in — log in so I can tailor advice to your account.");
					}
					return;
				}

				String username = context.getUsername();
				manager.recordUser(username, query);
				List<LlmMessage> history = manager.history();
				String memory = manager.memoryBlock(username);

				AgentReply reply = svc.chat(context, history, memory, this::onAgentStep);

				manager.recordAssistant(reply.getText());

				SidekickPanel panel = sidekickPanel;
				if (panel != null)
				{
					panel.showConversation(manager.current());
				}
			}
			catch (Exception e)
			{
				log.debug("Sidekick query error", e);
				SidekickPanel panel = sidekickPanel;
				if (panel != null)
				{
					panel.showError("Sidekick error: " + e.getMessage());
				}
			}
		});
	}

	/** Called by the voice pipeline once it has transcribed the player's speech. */
	private void onVoiceTranscript(String text)
	{
		ask(text == null ? "" : text.trim());
	}

	private String currentUsername()
	{
		try
		{
			return contextSource.fetch(null).getUsername();
		}
		catch (PlayerNotFoundException e)
		{
			return null;
		}
	}

	private void openPanel()
	{
		final NavigationButton nav = navButton;
		if (nav != null)
		{
			SwingUtilities.invokeLater(() -> clientToolbar.openPanel(nav));
		}
	}

	/** Reports one tool-call step: always to the sidebar (parity), and to game chat when enabled. */
	private void onAgentStep(String step)
	{
		SidekickPanel panel = sidekickPanel;
		if (panel != null)
		{
			panel.showProgress(step);
		}
		if (config.showToolCallsInChat())
		{
			postSystemMessage("<col=888888>" + step + "</col>");
		}
	}

	private void postSystemMessage(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage("[Sidekick] " + message)
			.build());
	}

	// ── Service lifecycle ──────────────────────────────────────────────────────────────────────────

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

		// Hiscores (WiseOldMan) — boss kill-counts and clue/minigame scores the live client cannot
		// see. Cached per-RSN so a chatty conversation reuses one upstream fetch.
		CloudPlayerContextSource hiscoresSource = new CloudPlayerContextSource(
			new WiseOldManClient(
				httpJson,
				HttpUrl.get("https://api.wiseoldman.net/v2"),
				new RateLimiter(5, 1, Duration.ofSeconds(2), clock)),
			new TtlCache<>(HISCORES_TTL, clock));

		// LLM
		LlmClient llm = buildLlmClient(config, okHttpClient, gson);

		// Agent — the recall tool reads from the long-lived conversation manager; the hiscores tool
		// looks the player up on WiseOldMan on demand.
		List<AgentTool> tools = List.of(
			new GrandExchangePriceTool(priceClient),
			new WikiSearchTool(wikiClient),
			new RecallConversationsTool(conversationManager),
			new HiscoresLookupTool(hiscoresSource, this::currentUsername));
		ToolRegistry registry = new ToolRegistry(tools);
		agentService = new AgentService(llm, registry, MAX_AGENT_STEPS);

		// Context: live client data
		chatService = new ChatService(contextSource, agentService, null);

		// Voice (optional)
		setupVoice(registry);

		log.info("OSRS Sidekick started (provider={}, model={}, voice={})",
			config.provider(), config.model(), config.voiceMode());
	}

	/**
	 * Wires push-to-talk to the configured voice backend: transcription (Gemini STT → text agent) or
	 * a realtime audio-to-audio agent. The same hotkey drives whichever backend is built.
	 */
	private void setupVoice(ToolRegistry registry)
	{
		VoiceMode mode = config.voiceMode();
		if (mode == VoiceMode.OFF)
		{
			return;
		}

		VoiceBackend backend = mode == VoiceMode.REALTIME
			? buildRealtimeBackend(registry)
			: buildTranscriptionBackend();
		if (backend == null)
		{
			return;
		}

		voiceBackend = backend;
		hotkeyListener = new HotkeyListener(() -> config.voiceHotkey())
		{
			@Override
			public void hotkeyPressed()
			{
				voiceBackend.pressToTalk();
			}

			@Override
			public void hotkeyReleased()
			{
				voiceBackend.releaseToTalk();
			}
		};
		keyManager.registerKeyListener(hotkeyListener);
		log.info("OSRS Sidekick voice activated ({})", mode);
	}

	/** Push-to-talk transcription: Gemini STT, then the normal text agent. */
	private VoiceBackend buildTranscriptionBackend()
	{
		String voiceKey = resolveVoiceApiKey(config);
		if (voiceKey == null)
		{
			log.warn("OSRS Sidekick: transcription voice needs a Gemini API key — voice disabled");
			return null;
		}
		OkHttpClient voiceHttpClient = okHttpClient.newBuilder()
			.readTimeout(60, TimeUnit.SECONDS)
			.build();
		GeminiVoiceClient voiceClient = new GeminiVoiceClient(
			voiceHttpClient, gson,
			HttpUrl.get("https://generativelanguage.googleapis.com"),
			voiceKey);
		return new VoiceService(voiceClient, chatMessageManager, this::onVoiceTranscript);
	}

	/**
	 * Realtime audio-to-audio voice agent. Currently xAI Grok, spoken to over the OpenAI Realtime
	 * protocol (see {@link OpenAiRealtimeSession}); OpenAI Realtime / Gemini Live slot in by adding a
	 * {@link RealtimeVoiceProvider} value and selecting the session/endpoint here.
	 */
	private VoiceBackend buildRealtimeBackend(ToolRegistry registry)
	{
		String key = resolveRealtimeApiKey(config);
		if (key == null)
		{
			log.warn("OSRS Sidekick: realtime voice needs an API key for the realtime provider — voice disabled");
			return null;
		}

		// No read timeout (a streaming socket stays open); keep-alive pings hold it up.
		OkHttpClient rtClient = okHttpClient.newBuilder()
			.pingInterval(Duration.ofSeconds(20))
			.readTimeout(Duration.ZERO)
			.build();

		// xAI realtime endpoint + model (best-effort; verify against live xAI docs).
		OpenAiRealtimeSession session = new OpenAiRealtimeSession(
			rtClient, gson, HttpUrl.get("https://api.x.ai/v1/realtime"), key, false);
		String rtModel = "grok-realtime";

		final NavigationButton capturedNav = navButton;
		return new RealtimeVoiceBackend(
			session, registry, contextSource, conversationManager,
			sidekickPanel,
			() -> SwingUtilities.invokeLater(() ->
			{
				if (capturedNav != null)
				{
					clientToolbar.openPanel(capturedNav);
				}
			}),
			this::onAgentStep,
			this::postSystemMessage,
			rtModel,
			null);
	}

	private void stopServices()
	{
		if (hotkeyListener != null)
		{
			keyManager.unregisterKeyListener(hotkeyListener);
			hotkeyListener = null;
		}
		if (voiceBackend != null)
		{
			voiceBackend.shutdown();
			voiceBackend = null;
		}
		agentService = null;
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
		// When the main provider is Gemini, the main key already is a Gemini key — reuse it so the
		// user never has to enter the same key twice.
		if (config.provider().supportsVoice())
		{
			String mainKey = config.apiKey();
			return (mainKey != null && !mainKey.trim().isEmpty()) ? mainKey.trim() : null;
		}
		return null;
	}

	/** Resolves the realtime provider's key, reusing the main key when the providers coincide (xAI). */
	private static String resolveRealtimeApiKey(SidekickPluginConfig config)
	{
		String key = config.realtimeApiKey();
		if (key != null && !key.trim().isEmpty())
		{
			return key.trim();
		}
		if (config.realtimeProvider() == RealtimeVoiceProvider.XAI
			&& config.provider() == LlmProviderOption.XAI)
		{
			String main = config.apiKey();
			return (main != null && !main.trim().isEmpty()) ? main.trim() : null;
		}
		return null;
	}

	private static BufferedImage buildIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xFF, 0x98, 0x1F));
		g.fillOval(0, 0, 15, 15);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.DIALOG, Font.BOLD, 10));
		FontMetrics fm = g.getFontMetrics();
		int x = (15 - fm.stringWidth("S")) / 2;
		int y = (15 - fm.getHeight()) / 2 + fm.getAscent();
		g.drawString("S", x, y);
		g.dispose();
		return img;
	}

	private static LlmClient buildLlmClient(SidekickPluginConfig config, OkHttpClient http, Gson gson)
	{
		LlmProviderOption option = config.provider();
		LlmProvider provider = option.provider();
		long maxTokens = config.maxTokens();

		String model = config.model();
		if (model == null || model.trim().isEmpty())
		{
			model = option.defaultModel();
		}

		if (provider == LlmProvider.GEMINI)
		{
			return GeminiClient.builder()
				.http(http)
				.gson(gson)
				.baseUrl(HttpUrl.get("https://generativelanguage.googleapis.com"))
				.apiKey(config.apiKey())
				.model(model)
				.maxTokens(maxTokens)
				.build();
		}

		if (provider == LlmProvider.XAI)
		{
			return XaiClient.builder()
				.http(http)
				.gson(gson)
				.baseUrl(HttpUrl.get("https://api.x.ai"))
				.apiKey(config.apiKey())
				.model(model)
				.maxTokens(maxTokens)
				.build();
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
