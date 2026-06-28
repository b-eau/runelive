package com.runelive.sidekick.context.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.runelive.sidekick.context.BankItem;
import com.runelive.sidekick.context.DiaryEntry;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.context.QuestEntry;
import com.runelive.sidekick.context.QuestStatus;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.vars.AccountType;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

public class ClientPlayerContextSourceTest
{
	private Client client;
	private Player player;
	private ClientPlayerContextSource source;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		player = mock(Player.class);
		source = new ClientPlayerContextSource(client);

		// Basic happy-path defaults
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(player.getCombatLevel()).thenReturn(100);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3210, 3424, 0)); // Lumbridge
		when(client.getAccountType()).thenReturn(AccountType.NORMAL);

		// All skills at level 1, 0 xp by default
		for (Skill skill : Skill.values())
		{
			when(client.getRealSkillLevel(skill)).thenReturn(1);
			when(client.getSkillExperience(skill)).thenReturn(0);
		}

		// Quest.getState() calls client.runScript(4029, id) then reads client.getIntStack()[0].
		// Switch: 1 → NOT_STARTED, 2 → FINISHED, default → IN_PROGRESS.
		// Return 1 so all quests default to NOT_STARTED.
		when(client.getIntStack()).thenReturn(new int[]{1});

		// All diary varbits = 0 (incomplete) by default — catch-all
		when(client.getVarbitValue(org.mockito.ArgumentMatchers.anyInt())).thenReturn(0);

		// No bank open
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(null);
	}

	@Test
	public void throwsBeforeFirstTick()
	{
		try
		{
			source.fetch("TestPlayer");
			fail("expected PlayerNotFoundException before first tick");
		}
		catch (PlayerNotFoundException e)
		{
			// expected
		}
	}

	@Test
	public void snapshotPopulatedAfterGameTick()
	{
		source.onGameTick(new GameTick());

		PlayerContext ctx = source.fetch("TestPlayer");
		assertNotNull(ctx);
		assertEquals("TestPlayer", ctx.getUsername());
		assertEquals("regular", ctx.getAccountType());
		assertEquals(100, ctx.getCombatLevel());
	}

	@Test
	public void readsSkillLevels()
	{
		when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(75);
		when(client.getSkillExperience(Skill.ATTACK)).thenReturn(1_300_000);
		when(client.getRealSkillLevel(Skill.SLAYER)).thenReturn(82);

		source.onGameTick(new GameTick());
		PlayerContext ctx = source.fetch("TestPlayer");

		assertNotNull(ctx.getSkills());
		assertEquals(75, ctx.skillLevel("attack"));
		assertEquals(1_300_000L, ctx.skill("attack").getExperience()); // long field, widened from int mock
		assertEquals(82, ctx.skillLevel("slayer"));
	}

	@Test
	public void mapAccountTypeIronman()
	{
		when(client.getAccountType()).thenReturn(AccountType.IRONMAN);
		source.onGameTick(new GameTick());

		assertTrue(source.fetch("TestPlayer").isIronman());
		assertEquals("ironman", source.fetch("TestPlayer").getAccountType());
	}

	@Test
	public void mapAccountTypeUltimateIronman()
	{
		when(client.getAccountType()).thenReturn(AccountType.ULTIMATE_IRONMAN);
		source.onGameTick(new GameTick());

		assertTrue(source.fetch("TestPlayer").isIronman());
		assertEquals("ultimate_ironman", source.fetch("TestPlayer").getAccountType());
	}

	@Test
	public void readsDiaryCompletionFromVarbits()
	{
		when(client.getVarbitValue(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.VARROCK_DIARY_ELITE_COMPLETE)).thenReturn(1);

		source.onGameTick(new GameTick());
		PlayerContext ctx = source.fetch("TestPlayer");

		assertNotNull(ctx.getDiaries());
		long ardougneComplete = ctx.getDiaries().stream()
			.filter(d -> "Ardougne".equals(d.getArea()) && d.isComplete())
			.count();
		assertEquals("Easy and Medium should be complete", 2, ardougneComplete);

		DiaryEntry varrockElite = ctx.getDiaries().stream()
			.filter(d -> "Varrock".equals(d.getArea()) && "Elite".equals(d.getTier()))
			.findFirst().orElseThrow(() -> new AssertionError("Varrock Elite not found"));
		assertTrue(varrockElite.isComplete());

		DiaryEntry varrockEasy = ctx.getDiaries().stream()
			.filter(d -> "Varrock".equals(d.getArea()) && "Easy".equals(d.getTier()))
			.findFirst().orElseThrow(() -> new AssertionError("Varrock Easy not found"));
		assertFalse(varrockEasy.isComplete());
	}

	@Test
	public void readsBankWhenOpen()
	{
		ItemContainer bank = mock(ItemContainer.class);
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(bank);

		Item whip = new Item(4151, 1);
		Item coins = new Item(995, 500_000);
		Item emptySlot = new Item(-1, 0);
		when(bank.getItems()).thenReturn(new Item[]{whip, coins, emptySlot});

		ItemComposition whipDef = mock(ItemComposition.class);
		when(whipDef.getName()).thenReturn("Abyssal whip");
		when(client.getItemDefinition(4151)).thenReturn(whipDef);

		ItemComposition coinsDef = mock(ItemComposition.class);
		when(coinsDef.getName()).thenReturn("Coins");
		when(client.getItemDefinition(995)).thenReturn(coinsDef);

		source.onGameTick(new GameTick());
		PlayerContext ctx = source.fetch("TestPlayer");

		assertNotNull(ctx.getBank());
		assertEquals(2, ctx.getBank().size());
		assertTrue(ctx.getBank().stream().anyMatch(i -> "Abyssal whip".equals(i.getName()) && i.getQuantity() == 1));
		assertTrue(ctx.getBank().stream().anyMatch(i -> "Coins".equals(i.getName()) && i.getQuantity() == 500_000));
	}

	@Test
	public void bankIsNullWhenNotOpen()
	{
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(null);
		source.onGameTick(new GameTick());
		assertNull(source.fetch("TestPlayer").getBank());
	}

	@Test
	public void readsQuestStates()
	{
		// Quest.values() returns 209 quests — we just verify the mapping logic is applied
		// by checking a few quest states reflect what the client returns.
		// We cannot easily pick a specific quest by name without an exhaustive search,
		// so we verify the list is non-empty and states are mapped correctly.
		source.onGameTick(new GameTick());
		PlayerContext ctx = source.fetch("TestPlayer");

		assertNotNull(ctx.getQuests());
		assertFalse(ctx.getQuests().isEmpty());
		// All quests default to NOT_STARTED in our mock
		assertTrue(ctx.getQuests().stream().allMatch(q -> q.getStatus() == QuestStatus.NOT_STARTED));
	}

	@Test
	public void knownLocationMapsToName()
	{
		// Lumbridge region (12850)
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3222, 3218, 0));
		source.onGameTick(new GameTick());
		String location = source.fetch("TestPlayer").getCurrentLocation();
		assertNotNull(location);
		// Should be a non-empty string (named or coordinate fallback)
		assertFalse(location.isEmpty());
	}

	@Test
	public void snapshotClearedOnLogout()
	{
		source.onGameTick(new GameTick());
		assertNotNull(source.fetch("TestPlayer"));

		GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		source.onGameStateChanged(logout);

		try
		{
			source.fetch("TestPlayer");
			fail("expected PlayerNotFoundException after logout");
		}
		catch (PlayerNotFoundException e)
		{
			// expected
		}
	}

	@Test
	public void readsCombatAchievementTiersAndPoints()
	{
		when(client.getVarbitValue(VarbitID.CA_POINTS)).thenReturn(540);
		when(client.getVarbitValue(VarbitID.CA_TIER_STATUS_EASY)).thenReturn(2);
		when(client.getVarbitValue(VarbitID.CA_TIER_STATUS_MEDIUM)).thenReturn(1);
		// Hard..Grandmaster stay 0 via the anyInt() default → "not started"

		source.onGameTick(new GameTick());
		PlayerContext ctx = source.fetch("TestPlayer");

		assertEquals(Integer.valueOf(540), ctx.getCombatTaskPoints());
		assertNotNull(ctx.getCombatTaskTiers());
		assertEquals("complete", ctx.getCombatTaskTiers().get("Easy"));
		assertEquals("in progress", ctx.getCombatTaskTiers().get("Medium"));
		assertEquals("not started", ctx.getCombatTaskTiers().get("Hard"));
	}

	@Test
	public void readsCollectionLogFromOpenInterface()
	{
		Widget header = mock(Widget.class);
		when(header.getText()).thenReturn("Collection Log - 567/1477");
		when(client.getWidget(InterfaceID.Collection.HEADER_TEXT)).thenReturn(header);

		source.onGameTick(new GameTick());
		PlayerContext ctx = source.fetch("TestPlayer");

		assertEquals(Integer.valueOf(567), ctx.getCollectionLogObtained());
		assertEquals(Integer.valueOf(1477), ctx.getCollectionLogTotal());
	}

	@Test
	public void collectionLogNullWhenInterfaceClosed()
	{
		// getWidget(...) returns null by default (unstubbed) → unknown
		source.onGameTick(new GameTick());
		PlayerContext ctx = source.fetch("TestPlayer");
		assertNull(ctx.getCollectionLogObtained());
		assertNull(ctx.getCollectionLogTotal());
	}

	@Test
	public void doesNotUpdateSnapshotWhenNotLoggedIn()
	{
		when(client.getGameState()).thenReturn(GameState.LOADING);
		source.onGameTick(new GameTick());
		// Snapshot should still be null — no update while loading
		try
		{
			source.fetch("TestPlayer");
			fail("expected PlayerNotFoundException");
		}
		catch (PlayerNotFoundException e)
		{
			// expected
		}
	}
}
