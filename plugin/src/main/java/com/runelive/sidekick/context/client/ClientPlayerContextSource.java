package com.runelive.sidekick.context.client;

import com.runelive.sidekick.context.BankItem;
import com.runelive.sidekick.context.DiaryEntry;
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
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InventoryID;
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
			.currentLocation(RegionNames.nameFor(localPlayer.getWorldLocation()))
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
