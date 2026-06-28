package com.runelive.sidekick.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerContextSource;
import com.runelive.sidekick.context.PlayerNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class HiscoresLookupToolTest
{
	private static PlayerContext withBosses()
	{
		Map<String, PlayerContext.BossStat> bosses = new LinkedHashMap<>();
		bosses.put("zulrah", new PlayerContext.BossStat(1500, 1000));
		bosses.put("vorkath", new PlayerContext.BossStat(500, 2000));
		// tormented_demons intentionally absent → effectively zero
		Map<String, PlayerContext.ActivityStat> activities = new LinkedHashMap<>();
		activities.put("clue_scrolls_hard", new PlayerContext.ActivityStat(80, 5000));

		return PlayerContext.builder()
			.username("Zezima").accountType("regular").build("main")
			.skills(Map.of()).bosses(bosses).activities(activities)
			.build();
	}

	private static HiscoresLookupTool tool(PlayerContextSource source, String username)
	{
		return new HiscoresLookupTool(source, () -> username);
	}

	@Test
	public void listsBossKcAndActivityScores()
	{
		HiscoresLookupTool tool = tool(username -> withBosses(), "Zezima");
		String out = tool.execute(new JsonObject());

		assertTrue(out.contains("Zulrah 1500"));
		assertTrue(out.contains("Vorkath 500"));
		assertTrue(out.contains("Clue Scrolls Hard 80"));
		assertTrue("explains that unlisted bosses are zero", out.toLowerCase().contains("zero"));
		assertFalse("Tormented Demons is not on the hiscores", out.contains("Tormented"));
	}

	@Test
	public void filterNarrowsResults()
	{
		HiscoresLookupTool tool = tool(username -> withBosses(), "Zezima");
		JsonObject input = new JsonObject();
		input.addProperty("filter", "zulrah");
		String out = tool.execute(input);

		assertTrue(out.contains("Zulrah 1500"));
		assertFalse(out.contains("Vorkath"));
	}

	@Test
	public void reportsZeroWhenFilterMatchesNothing()
	{
		HiscoresLookupTool tool = tool(username -> withBosses(), "Zezima");
		JsonObject input = new JsonObject();
		input.addProperty("filter", "tormented");
		String out = tool.execute(input);

		assertTrue(out.toLowerCase().contains("zero"));
	}

	@Test
	public void handlesUntrackedPlayer()
	{
		PlayerContextSource notFound = username ->
		{
			throw new PlayerNotFoundException(username);
		};
		String out = tool(notFound, "NobodySpecial").execute(new JsonObject());
		assertTrue(out.toLowerCase().contains("not tracked"));
		assertTrue(out.toLowerCase().contains("zero"));
	}

	@Test
	public void handlesNoPlayerLoggedIn()
	{
		String out = tool(username -> withBosses(), null).execute(new JsonObject());
		assertTrue(out.contains("No player is logged in"));
	}

	@Test
	public void handlesServiceFailureGracefully()
	{
		PlayerContextSource failing = username ->
		{
			throw new RuntimeException("connection reset");
		};
		String out = tool(failing, "Zezima").execute(new JsonObject());
		assertTrue(out.toLowerCase().contains("couldn't reach"));
	}
}
