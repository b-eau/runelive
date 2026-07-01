package com.runelive.sidekick.goal;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@link GoalStore} that round-trips through JSON in memory (like disk) for tests — no RuneLite. */
class InMemoryGoalStore extends GoalStore
{
	private final Gson gson = new Gson();
	private final Map<String, String> byUser = new LinkedHashMap<>();

	InMemoryGoalStore()
	{
		super(new Gson());
	}

	@Override
	public List<Goal> load(String username)
	{
		String json = byUser.get(key(username));
		if (json == null)
		{
			return new ArrayList<>();
		}
		return new ArrayList<>(Arrays.asList(gson.fromJson(json, Goal[].class)));
	}

	@Override
	public void save(String username, List<Goal> goals)
	{
		byUser.put(key(username), gson.toJson(goals));
	}

	private static String key(String username)
	{
		return username == null ? "" : username.toLowerCase();
	}
}
