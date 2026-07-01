package com.runelive.sidekick.goal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages the player's long-term goals: add, list, complete, and render the block of active goals
 * embedded in the system prompt so the agent keeps advice aligned with what the player is working
 * toward. Goals are scoped per account. Backed by {@link GoalStore}; calls come from the plugin's
 * query-executor thread (and occasionally the EDT), so mutators are {@code synchronized}.
 */
public class GoalService
{
	private final GoalStore store;

	public GoalService(GoalStore store)
	{
		this.store = store;
	}

	public synchronized Goal add(String username, String text)
	{
		List<Goal> goals = store.load(username);
		Goal goal = new Goal(UUID.randomUUID().toString().substring(0, 6), text.trim(),
			System.currentTimeMillis());
		goals.add(goal);
		store.save(username, goals);
		return goal;
	}

	/** Marks the first active goal matching {@code idOrText} (id, or a case-insensitive substring). */
	public synchronized Goal complete(String username, String idOrText)
	{
		List<Goal> goals = store.load(username);
		Goal match = find(goals, idOrText);
		if (match == null)
		{
			return null;
		}
		match.markDone(System.currentTimeMillis());
		store.save(username, goals);
		return match;
	}

	public synchronized List<Goal> active(String username)
	{
		return store.load(username).stream().filter(g -> !g.isDone()).collect(Collectors.toList());
	}

	/**
	 * A short block listing the player's active goals for the system prompt, or {@code null} when
	 * there are none.
	 */
	public synchronized String goalsBlock(String username)
	{
		List<Goal> active = active(username);
		if (active.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder("THE PLAYER'S ACTIVE GOALS (keep advice aligned with "
			+ "these, note progress, and use manage_goals to add or complete a goal when they ask):\n");
		for (Goal g : active)
		{
			sb.append("- [").append(g.getId()).append("] ").append(g.getText()).append('\n');
		}
		return sb.toString();
	}

	private static Goal find(List<Goal> goals, String idOrText)
	{
		if (idOrText == null)
		{
			return null;
		}
		String needle = idOrText.trim().toLowerCase();
		List<Goal> active = new ArrayList<>();
		for (Goal g : goals)
		{
			if (!g.isDone())
			{
				active.add(g);
			}
		}
		for (Goal g : active)
		{
			if (g.getId().equalsIgnoreCase(idOrText.trim()))
			{
				return g;
			}
		}
		for (Goal g : active)
		{
			if (g.getText().toLowerCase().contains(needle))
			{
				return g;
			}
		}
		return null;
	}
}
