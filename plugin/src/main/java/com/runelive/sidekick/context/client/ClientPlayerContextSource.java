package com.runelive.sidekick.context.client;

import com.runelive.sidekick.context.BankItem;
import com.runelive.sidekick.context.DiaryEntry;
import com.runelive.sidekick.context.InventoryItem;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerContextSource;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.context.QuestEntry;
import com.runelive.sidekick.context.QuestStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.VarPlayer;
import net.runelite.api.vars.AccountType;
import net.runelite.client.eventbus.Subscribe;

/**
 * A {@link PlayerContextSource} backed by the live RuneLite client.
 *
 * <p>All client-thread reads happen inside {@link #onGameTick}, which writes a {@code volatile}
 * snapshot. The {@link #fetch} method (called from the agent's background thread) reads that
 * snapshot without touching the client thread, so there is no synchronisation problem.
 *
 * <p>The plugin registers/unregisters this class with the EventBus in its {@code startUp}/
 * {@code shutDown} — do not register it elsewhere. Bank data is only available after the player
 * opens their bank; the field will be {@code null} otherwise.
 */
@Slf4j
public class ClientPlayerContextSource implements PlayerContextSource
{
	private final Client client;

	/** Written only on the client thread (GameTick), read from any thread. */
	private volatile PlayerContext snapshot;

	@Inject
	public ClientPlayerContextSource(Client client)
	{
		this.client = client;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		try
		{
			snapshot = buildSnapshot(localPlayer);
		}
		catch (Exception e)
		{
			log.debug("Failed to build player snapshot", e);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			snapshot = null;
		}
	}

	/**
	 * Returns the most recent snapshot. The snapshot is refreshed every game tick once the player
	 * is logged in, so it is current to within ~600 ms of the request.
	 *
	 * @param username ignored — the context is always for the logged-in player
	 * @throws PlayerNotFoundException if no snapshot exists yet (e.g. called before the first tick)
	 */
	@Override
	public PlayerContext fetch(String username)
	{
		PlayerContext ctx = snapshot;
		if (ctx == null)
		{
			throw new PlayerNotFoundException("No client snapshot yet — is a player logged in?");
		}
		return ctx;
	}

	// ── Private ──────────────────────────────────────────────────────────────────────────────────

	private PlayerContext buildSnapshot(Player localPlayer)
	{
		String username = localPlayer.getName();
		if (username == null)
		{
			username = "Unknown";
		}

		Map<String, PlayerContext.SkillStat> skills = readSkills();
		int combatLevel = localPlayer.getCombatLevel();
		int totalLevel = sumTotalLevel(skills);
		long totalXp = sumTotalXp(skills);

		WorldPoint location = localPlayer.getWorldLocation();
		boolean inInstance = client.isInInstancedRegion();

		return PlayerContext.builder()
			.username(username)
			.accountType(accountTypeString(client.getAccountType()))
			.build("main")
			.combatLevel(combatLevel)
			.totalLevel(totalLevel)
			.totalExperience(totalXp)
			.efficientHoursPlayed(0)
			.efficientHoursBossed(0)
			.skills(skills)
			.bosses(Map.of())
			.activities(Map.of())
			.bank(readBank())
			.quests(readQuests())
			.diaries(readDiaries())
			.currentLocation(buildLocationString(location, inInstance))
			// Live game state
			.currentHp(client.getBoostedSkillLevel(Skill.HITPOINTS))
			.maxHp(client.getRealSkillLevel(Skill.HITPOINTS))
			.currentPrayer(client.getBoostedSkillLevel(Skill.PRAYER))
			.maxPrayer(client.getRealSkillLevel(Skill.PRAYER))
			.runEnergy(client.getEnergy() / 100)
			.weight((int) Math.round(client.getWeight()))
			.specialAttack(client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10)
			.spellbook(decodeSpellbook(client.getVarbitValue(VarbitID.SPELLBOOK)))
			.activePrayers(readActivePrayers())
			.boostedSkills(readBoostedSkills())
			.npcTarget(readNpcTarget(localPlayer))
			.slayerTask(readSlayerTask())
			.inventory(readInventory())
			.equipment(readEquipment())
			.wildernessLevel(detectWildernessLevel(location, inInstance))
			.inInstance(inInstance)
			.build();
	}

	private Map<String, PlayerContext.SkillStat> readSkills()
	{
		Map<String, PlayerContext.SkillStat> skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			int level = client.getRealSkillLevel(skill);
			long xp = (long) client.getSkillExperience(skill);
			skills.put(skill.name().toLowerCase(), new PlayerContext.SkillStat(level, xp, 0));
		}
		return skills;
	}

	/**
	 * Reads the bank container. Returns {@code null} when the bank is not open (container not
	 * loaded into memory). The snapshot retains the last-seen bank between openings.
	 */
	private List<BankItem> readBank()
	{
		ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
		if (bankContainer == null)
		{
			// Retain previous bank data if we have it
			PlayerContext prev = snapshot;
			return prev != null ? prev.getBank() : null;
		}
		Item[] items = bankContainer.getItems();
		if (items == null)
		{
			return null;
		}
		List<BankItem> result = new ArrayList<>();
		for (Item item : items)
		{
			if (item.getId() <= 0)
			{
				continue;
			}
			String name = client.getItemDefinition(item.getId()).getName();
			// RuneLite returns "null" (the string) for placeholder items
			if (name != null && !name.equals("null") && !name.isEmpty())
			{
				result.add(new BankItem(name, item.getQuantity()));
			}
		}
		return result.isEmpty() ? null : result;
	}

	private List<QuestEntry> readQuests()
	{
		List<QuestEntry> entries = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			entries.add(new QuestEntry(quest.getName(), mapQuestState(quest.getState(client))));
		}
		return entries;
	}

	private List<DiaryEntry> readDiaries()
	{
		List<DiaryEntry> entries = new ArrayList<>();
		for (DiaryArea area : DiaryArea.values())
		{
			for (DiaryTier tier : DiaryTier.values())
			{
				int varbitId = area.varbitFor(tier);
				if (varbitId < 0)
				{
					continue;
				}
				boolean complete = client.getVarbitValue(varbitId) == 1;
				entries.add(new DiaryEntry(area.getDisplayName(), tier.getDisplayName(), complete));
			}
		}
		return entries;
	}

	private List<String> readActivePrayers()
	{
		List<String> active = new ArrayList<>();
		for (Prayer prayer : Prayer.values())
		{
			if (client.isPrayerActive(prayer))
			{
				active.add(formatPrayerName(prayer.name()));
			}
		}
		return active;
	}

	private Map<String, Integer> readBoostedSkills()
	{
		Map<String, Integer> boosted = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL || skill == Skill.HITPOINTS || skill == Skill.PRAYER)
			{
				continue;
			}
			int real = client.getRealSkillLevel(skill);
			int current = client.getBoostedSkillLevel(skill);
			if (current != real)
			{
				boosted.put(skill.name().toLowerCase(), current);
			}
		}
		return boosted.isEmpty() ? null : boosted;
	}

	private String readNpcTarget(Player localPlayer)
	{
		Actor target = localPlayer.getInteracting();
		if (!(target instanceof NPC))
		{
			return null;
		}
		NPC npc = (NPC) target;
		String name = npc.getName();
		if (name == null)
		{
			return null;
		}
		int combatLevel = npc.getCombatLevel();
		return combatLevel > 0 ? name + " (lvl " + combatLevel + ")" : name;
	}

	private String readSlayerTask()
	{
		int taskSize = client.getVarpValue(VarPlayer.SLAYER_TASK_SIZE);
		if (taskSize <= 0)
		{
			return null;
		}
		int creatureId = client.getVarpValue(VarPlayer.SLAYER_TASK_CREATURE);
		if (creatureId <= 0)
		{
			return null;
		}
		String creatureName = SlayerTaskNames.nameFor(creatureId);
		return taskSize + " × " + creatureName;
	}

	private List<InventoryItem> readInventory()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv == null)
		{
			return null;
		}
		Item[] items = inv.getItems();
		if (items == null)
		{
			return new ArrayList<>();
		}
		// Aggregate stacks of the same item name
		Map<String, int[]> aggregated = new LinkedHashMap<>();
		Map<String, Boolean> notedMap = new LinkedHashMap<>();
		for (Item item : items)
		{
			if (item.getId() <= 0)
			{
				continue;
			}
			ItemComposition def = client.getItemDefinition(item.getId());
			boolean noted = def.getNote() != -1;
			String name;
			if (noted)
			{
				ItemComposition baseDef = client.getItemDefinition(def.getNote());
				name = baseDef.getName();
			}
			else
			{
				name = def.getName();
			}
			if (name == null || name.equals("null") || name.isEmpty())
			{
				continue;
			}
			String key = noted ? name + "\0noted" : name;
			aggregated.computeIfAbsent(key, k -> new int[]{0})[0] += item.getQuantity();
			notedMap.put(key, noted);
		}
		List<InventoryItem> result = new ArrayList<>();
		for (Map.Entry<String, int[]> e : aggregated.entrySet())
		{
			String key = e.getKey();
			boolean noted = Boolean.TRUE.equals(notedMap.get(key));
			String displayName = noted ? key.replace("\0noted", "") : key;
			result.add(new InventoryItem(displayName, e.getValue()[0], noted));
		}
		return result;
	}

	private Map<String, String> readEquipment()
	{
		ItemContainer equip = client.getItemContainer(InventoryID.WORN);
		if (equip == null)
		{
			return null;
		}
		Map<String, String> slots = new LinkedHashMap<>();
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			Item item = equip.getItem(slot.getSlotIdx());
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			String name = client.getItemDefinition(item.getId()).getName();
			if (name != null && !name.equals("null") && !name.isEmpty())
			{
				slots.put(slot.name().toLowerCase(), name);
			}
		}
		return slots.isEmpty() ? null : slots;
	}

	private String buildLocationString(WorldPoint point, boolean inInstance)
	{
		if (point == null)
		{
			return null;
		}
		String base = RegionNames.nameFor(point);
		Integer wildLevel = detectWildernessLevel(point, inInstance);
		if (wildLevel != null)
		{
			return base + " (Wilderness level " + wildLevel + ")";
		}
		if (inInstance)
		{
			return base + " (instanced)";
		}
		return base;
	}

	private static Integer detectWildernessLevel(WorldPoint point, boolean inInstance)
	{
		if (point == null || inInstance || point.getPlane() != 0)
		{
			return null;
		}
		int x = point.getX();
		int y = point.getY();
		if (x >= 2944 && x <= 3392 && y >= 3527 && y <= 4000)
		{
			int level = (y - 3526) / 8 + 1;
			return Math.min(level, 56);
		}
		return null;
	}

	private static String decodeSpellbook(int varbit)
	{
		switch (varbit)
		{
			case 1: return "Ancient";
			case 2: return "Lunar";
			case 3: return "Arceuus";
			default: return "Standard";
		}
	}

	private static String formatPrayerName(String enumName)
	{
		String name = enumName.startsWith("RP_") ? enumName.substring(3) : enumName;
		String[] parts = name.split("_");
		StringBuilder sb = new StringBuilder();
		for (String part : parts)
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			if (!part.isEmpty())
			{
				sb.append(Character.toUpperCase(part.charAt(0)));
				if (part.length() > 1)
				{
					sb.append(part.substring(1).toLowerCase());
				}
			}
		}
		return sb.toString();
	}

	private static QuestStatus mapQuestState(QuestState state)
	{
		if (state == QuestState.FINISHED)
		{
			return QuestStatus.COMPLETE;
		}
		if (state == QuestState.IN_PROGRESS)
		{
			return QuestStatus.IN_PROGRESS;
		}
		return QuestStatus.NOT_STARTED;
	}

	private static String accountTypeString(AccountType type)
	{
		if (type == null)
		{
			return "regular";
		}
		switch (type)
		{
			case IRONMAN: return "ironman";
			case ULTIMATE_IRONMAN: return "ultimate_ironman";
			case HARDCORE_IRONMAN: return "hardcore_ironman";
			case GROUP_IRONMAN: return "group_ironman";
			case HARDCORE_GROUP_IRONMAN: return "hardcore_group_ironman";
			default: return "regular";
		}
	}

	private static int sumTotalLevel(Map<String, PlayerContext.SkillStat> skills)
	{
		return skills.values().stream().mapToInt(PlayerContext.SkillStat::getLevel).sum();
	}

	private static long sumTotalXp(Map<String, PlayerContext.SkillStat> skills)
	{
		return skills.values().stream().mapToLong(PlayerContext.SkillStat::getExperience).sum();
	}
}
