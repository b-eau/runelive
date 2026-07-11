package com.osrssidekick;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "OSRS Sidekick Sync",
	description = "Syncs your stats, quests, bank, and kill counts to your OSRS Sidekick dashboard",
	tags = {"sync", "stats", "tracker", "sidekick", "dashboard"}
)
public class SidekickSyncPlugin extends Plugin
{
	private static final Pattern KILL_COUNT_PATTERN =
		Pattern.compile("Your (?:completed )?(.+?) (?:kill count is|count is|success count is): ([\\d,]+)");

	private static final long SKILLS_HEARTBEAT_MS = 15 * 60 * 1000L;
	private static final long CONTAINER_MIN_INTERVAL_MS = 30 * 1000L;
	private static final int LOGIN_SYNC_DELAY_TICKS = 8;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private SidekickSyncConfig config;

	@Inject
	private SidekickApiClient api;

	@Inject
	private ConfigManager configManager;

	private ScheduledExecutorService executor;
	private ScheduledFuture<?> flushFuture;
	private ScheduledFuture<?> heartbeatFuture;
	private ScheduledFuture<?> linkPollFuture;

	private final ConcurrentLinkedQueue<JsonObject> pendingEvents = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean flushing = new AtomicBoolean(false);

	private final Map<Skill, Integer> lastLevels = new EnumMap<>(Skill.class);
	private final Map<String, Long> lastContainerSyncAt = new ConcurrentHashMap<>();
	private final Map<String, Integer> lastContainerHash = new ConcurrentHashMap<>();

	private volatile long lastSkillsSyncAt;
	private volatile long lastSyncedTotalXp = -1;
	private volatile boolean levelUpPending;
	private volatile int loginSyncCountdown = -1;

	// snapshot of client identity, safe to read off the client thread
	private volatile long accountHash = -1;
	private volatile String displayName;
	private volatile String profileKind = "STANDARD";
	private volatile String accountType = "REGULAR";

	@Override
	protected void startUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		flushFuture = executor.scheduleWithFixedDelay(this::flush, 10, 10, TimeUnit.SECONDS);
		heartbeatFuture = executor.scheduleWithFixedDelay(this::heartbeat, 60, 60, TimeUnit.SECONDS);
		log.info("OSRS Sidekick Sync started");
	}

	@Override
	protected void shutDown()
	{
		if (flushFuture != null)
		{
			flushFuture.cancel(true);
		}
		if (heartbeatFuture != null)
		{
			heartbeatFuture.cancel(true);
		}
		if (linkPollFuture != null)
		{
			linkPollFuture.cancel(true);
		}
		if (executor != null)
		{
			executor.shutdownNow();
		}
		pendingEvents.clear();
		lastLevels.clear();
		lastContainerSyncAt.clear();
		lastContainerHash.clear();
	}

	@Provides
	SidekickSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SidekickSyncConfig.class);
	}

	// ------------------------------------------------------------ game events

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loginSyncCountdown = LOGIN_SYNC_DELAY_TICKS;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			loginSyncCountdown = -1;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (loginSyncCountdown < 0)
		{
			return;
		}
		if (loginSyncCountdown-- > 0)
		{
			return;
		}
		// A few ticks after login: identity, skills, and quest log are loaded.
		refreshIdentity();
		enqueueSkillsSnapshot();
		enqueueQuestSnapshot();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!SidekickSyncConfig.GROUP.equals(event.getGroup()) || !"linkAccount".equals(event.getKey()))
		{
			return;
		}
		if (!Boolean.parseBoolean(event.getNewValue()))
		{
			return;
		}
		// The checkbox is a momentary "button": untick it right away, then run
		// the link flow (progress is reported via game chat messages).
		configManager.setConfiguration(SidekickSyncConfig.GROUP, "linkAccount", false);
		beginLinking();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Integer previous = lastLevels.put(event.getSkill(), event.getLevel());
		if (previous != null && event.getLevel() > previous)
		{
			levelUpPending = true;
			JsonObject payload = new JsonObject();
			payload.addProperty("skill", event.getSkill().getName().toLowerCase());
			payload.addProperty("level", event.getLevel());
			enqueue("LEVEL_UP", payload);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		String type;
		if (event.getContainerId() == InventoryID.BANK)
		{
			type = "BANK";
		}
		else if (event.getContainerId() == InventoryID.INV)
		{
			type = "INVENTORY";
		}
		else if (event.getContainerId() == InventoryID.WORN)
		{
			type = "EQUIPMENT";
		}
		else
		{
			return;
		}

		ItemContainer container = event.getItemContainer();
		if (container == null)
		{
			return;
		}

		// Aggregate quantities per item id (bank tabs can repeat placeholders).
		Map<Integer, Long> quantities = new java.util.LinkedHashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			quantities.merge(item.getId(), (long) item.getQuantity(), Long::sum);
		}

		int hash = quantities.hashCode();
		long now = System.currentTimeMillis();
		Integer prevHash = lastContainerHash.get(type);
		Long lastAt = lastContainerSyncAt.get(type);
		if (prevHash != null && prevHash == hash)
		{
			return; // unchanged
		}
		if (lastAt != null && now - lastAt < CONTAINER_MIN_INTERVAL_MS && !"BANK".equals(type))
		{
			return; // rate-limit chatty inventory/equipment updates
		}
		lastContainerHash.put(type, hash);
		lastContainerSyncAt.put(type, now);

		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		for (Map.Entry<Integer, Long> entry : quantities.entrySet())
		{
			JsonObject item = new JsonObject();
			item.addProperty("id", entry.getKey());
			item.addProperty("qty", entry.getValue());
			items.add(item);
		}
		JsonObject payload = new JsonObject();
		payload.add("items", items);
		enqueue(type, payload);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		Matcher matcher = KILL_COUNT_PATTERN.matcher(Text.removeTags(event.getMessage()));
		if (!matcher.find())
		{
			return;
		}
		String boss = matcher.group(1)
			.toLowerCase()
			.replace("'", "")
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("^_|_$", "");
		int kc = Integer.parseInt(matcher.group(2).replace(",", ""));

		JsonObject payload = new JsonObject();
		payload.addProperty("boss", boss);
		payload.addProperty("kc", kc);
		enqueue("KILL_COUNT", payload);
	}

	// -------------------------------------------------------------- snapshots

	/** Must run on the client thread. */
	private void refreshIdentity()
	{
		accountHash = client.getAccountHash();
		if (client.getLocalPlayer() != null)
		{
			displayName = client.getLocalPlayer().getName();
		}

		var worldTypes = client.getWorldType();
		if (worldTypes.contains(WorldType.DEADMAN))
		{
			profileKind = "DEADMAN";
		}
		else if (worldTypes.contains(WorldType.SEASONAL))
		{
			profileKind = "LEAGUES";
		}
		else if (worldTypes.contains(WorldType.BETA_WORLD))
		{
			profileKind = "BETA";
		}
		else
		{
			profileKind = "STANDARD";
		}

		switch (client.getVarbitValue(VarbitID.IRONMAN))
		{
			case 1:
				accountType = "IRONMAN";
				break;
			case 2:
				accountType = "ULTIMATE_IRONMAN";
				break;
			case 3:
				accountType = "HARDCORE_IRONMAN";
				break;
			case 4:
				accountType = "GROUP_IRONMAN";
				break;
			case 5:
				accountType = "HARDCORE_GROUP_IRONMAN";
				break;
			case 6:
				accountType = "UNRANKED_GROUP_IRONMAN";
				break;
			default:
				accountType = "REGULAR";
				break;
		}
	}

	/** Must run on the client thread. */
	private void enqueueSkillsSnapshot()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		JsonObject skills = new JsonObject();
		long totalXp = 0;
		for (Skill skill : Skill.values())
		{
			int xp = client.getSkillExperience(skill);
			int level = client.getRealSkillLevel(skill);
			totalXp += xp;
			JsonObject entry = new JsonObject();
			entry.addProperty("xp", xp);
			entry.addProperty("level", level);
			skills.add(skill.getName().toLowerCase(), entry);
		}
		JsonObject payload = new JsonObject();
		payload.add("skills", skills);
		enqueue("SKILLS", payload);

		lastSkillsSyncAt = System.currentTimeMillis();
		lastSyncedTotalXp = totalXp;
		levelUpPending = false;
	}

	/** Must run on the client thread. */
	private void enqueueQuestSnapshot()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		com.google.gson.JsonArray quests = new com.google.gson.JsonArray();
		for (Quest quest : Quest.values())
		{
			JsonObject entry = new JsonObject();
			entry.addProperty("name", quest.getName());
			entry.addProperty("state", quest.getState(client).name());
			quests.add(entry);
		}
		JsonObject payload = new JsonObject();
		payload.add("quests", quests);
		enqueue("QUESTS", payload);
	}

	private void enqueue(String type, JsonObject payload)
	{
		JsonObject event = new JsonObject();
		event.addProperty("type", type);
		event.addProperty("occurredAt", Instant.now().toString());
		event.addProperty("dedupeKey", UUID.randomUUID().toString());
		event.add("payload", payload);
		pendingEvents.add(event);
	}

	// ------------------------------------------------------------------ sync

	private void heartbeat()
	{
		if (!syncActive())
		{
			return;
		}
		boolean heartbeatDue = System.currentTimeMillis() - lastSkillsSyncAt >= SKILLS_HEARTBEAT_MS;
		if (!levelUpPending && !heartbeatDue)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			long totalXp = client.getOverallExperience();
			if (levelUpPending || totalXp != lastSyncedTotalXp)
			{
				refreshIdentity();
				enqueueSkillsSnapshot();
			}
		});
	}

	private void flush()
	{
		if (!syncActive() || pendingEvents.isEmpty())
		{
			return;
		}
		String token = apiToken();
		if (token == null)
		{
			return;
		}
		if (!flushing.compareAndSet(false, true))
		{
			return;
		}

		List<JsonObject> batch = new ArrayList<>();
		JsonObject event;
		while (batch.size() < 150 && (event = pendingEvents.poll()) != null)
		{
			batch.add(event);
		}
		if (batch.isEmpty())
		{
			flushing.set(false);
			return;
		}

		api.ingest(token, profileKind, accountType, displayName, batch,
			() -> flushing.set(false),
			reason ->
			{
				if (!"unauthorized".equals(reason))
				{
					pendingEvents.addAll(batch); // retry later; dedupeKey keeps it idempotent
				}
				flushing.set(false);
			});
	}

	boolean syncActive()
	{
		return config.syncEnabled() && accountHash != -1 && apiToken() != null;
	}

	String apiToken()
	{
		long hash = accountHash;
		if (hash == -1)
		{
			return null;
		}
		return configManager.getConfiguration(SidekickSyncConfig.GROUP, "token." + hash);
	}

	// -------------------------------------------------------------- linking

	/** Reports link progress in game chat (and the log for the login screen edge). */
	private void status(String message)
	{
		log.debug("link status: {}", message);
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "", "Sidekick: " + message, null);
			}
		});
	}

	private void beginLinking()
	{
		if (!config.syncEnabled())
		{
			status("Enable syncing in the plugin settings first, then tick Link account again.");
			return;
		}
		if (apiToken() != null)
		{
			status("This character is already linked — open " + config.backendUrl() + " to see your dashboard.");
			return;
		}
		long hash = accountHash;
		String name = displayName;
		if (hash == -1 || name == null)
		{
			status("Log in to the game first, then tick Link account again.");
			return;
		}
		status("Contacting Sidekick…");
		api.startLink(hash, name,
			start ->
			{
				LinkBrowser.browse(start.getLinkUrl());
				status("Complete the sign-in in your browser. Code: " + start.getCode());
				pollForToken(start, hash, 0);
			},
			this::status);
	}

	private void pollForToken(SidekickApiClient.LinkStart start, long hash, int attempt)
	{
		if (attempt > 200 || executor == null || executor.isShutdown())
		{
			return;
		}
		linkPollFuture = executor.schedule(() ->
			api.pollLink(start.getCode(), start.getPollSecret(),
				token ->
				{
					if (token != null)
					{
						configManager.setConfiguration(SidekickSyncConfig.GROUP, "token." + hash, token);
						status("Linked! Syncing is active.");
						// Push a first full snapshot right away.
						clientThread.invoke(() ->
						{
							refreshIdentity();
							enqueueSkillsSnapshot();
							enqueueQuestSnapshot();
						});
					}
					else
					{
						pollForToken(start, hash, attempt + 1);
					}
				},
				this::status), 3, TimeUnit.SECONDS);
	}
}
