package com.runelive.sidekick.goal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoalServiceTest
{
	@Test
	public void addsAndListsActiveGoals()
	{
		GoalService service = new GoalService(new InMemoryGoalStore());
		service.add("Zezima", "Quest cape");
		service.add("Zezima", "Fire cape");

		assertEquals(2, service.active("Zezima").size());
		String block = service.goalsBlock("Zezima");
		assertTrue(block.contains("ACTIVE GOALS"));
		assertTrue(block.contains("Quest cape"));
		assertTrue(block.contains("Fire cape"));
	}

	@Test
	public void goalsAreScopedPerAccount()
	{
		GoalService service = new GoalService(new InMemoryGoalStore());
		service.add("Zezima", "Quest cape");
		assertTrue(service.active("Woox").isEmpty());
	}

	@Test
	public void completeByPhraseRemovesFromActive()
	{
		GoalService service = new GoalService(new InMemoryGoalStore());
		service.add("Zezima", "Get a fire cape");

		Goal done = service.complete("Zezima", "fire cape");
		assertEquals("Get a fire cape", done.getText());
		assertTrue(done.isDone());
		assertTrue(service.active("Zezima").isEmpty());
	}

	@Test
	public void completeByIdWorks()
	{
		GoalService service = new GoalService(new InMemoryGoalStore());
		Goal g = service.add("Zezima", "Barrows gloves");
		assertEquals("Barrows gloves", service.complete("Zezima", g.getId()).getText());
		assertTrue(service.active("Zezima").isEmpty());
	}

	@Test
	public void completeUnknownReturnsNull()
	{
		GoalService service = new GoalService(new InMemoryGoalStore());
		service.add("Zezima", "Quest cape");
		assertNull(service.complete("Zezima", "infernal cape"));
	}

	@Test
	public void goalsBlockIsNullWhenNoActiveGoals()
	{
		GoalService service = new GoalService(new InMemoryGoalStore());
		assertNull(service.goalsBlock("Zezima"));
		Goal g = service.add("Zezima", "done goal");
		service.complete("Zezima", g.getId());
		assertNull("all goals done → no block", service.goalsBlock("Zezima"));
		assertFalse(service.goalsBlock("Zezima") != null);
	}
}
