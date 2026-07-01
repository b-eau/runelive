package com.runelive.sidekick.goal;

import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import org.junit.Test;

public class GoalsToolTest
{
	private static JsonObject input(String action, String goal)
	{
		JsonObject o = new JsonObject();
		o.addProperty("action", action);
		if (goal != null)
		{
			o.addProperty("goal", goal);
		}
		return o;
	}

	@Test
	public void addsListsAndCompletesGoals()
	{
		GoalService service = new GoalService(new InMemoryGoalStore());
		GoalsTool tool = new GoalsTool(service, () -> "Zezima");

		assertTrue(tool.execute(input("add", "Quest cape")).contains("Added goal"));
		assertTrue(service.active("Zezima").stream().anyMatch(g -> g.getText().equals("Quest cape")));

		assertTrue(tool.execute(input("list", null)).contains("Quest cape"));

		assertTrue(tool.execute(input("complete", "quest")).toLowerCase().contains("done"));
		assertTrue(service.active("Zezima").isEmpty());
	}

	@Test
	public void reportsNoPlayer()
	{
		GoalsTool tool = new GoalsTool(new GoalService(new InMemoryGoalStore()), () -> null);
		assertTrue(tool.execute(input("list", null)).contains("No player"));
	}

	@Test
	public void listWhenEmpty()
	{
		GoalsTool tool = new GoalsTool(new GoalService(new InMemoryGoalStore()), () -> "Zezima");
		assertTrue(tool.execute(input("list", null)).contains("No active goals"));
	}
}
