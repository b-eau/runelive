package com.runelive.sidekick.goal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runelive.sidekick.agent.tools.AgentTool;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tool: lets the agent record, list and complete the player's long-term goals so it can set a goal
 * when the player asks ("set a goal to get quest cape"), review progress, and mark goals done. Goals
 * persist across sessions and are also injected into the system prompt, so the agent stays aligned
 * with what the player is working toward.
 */
public class GoalsTool implements AgentTool
{
	private final GoalService goals;
	private final Supplier<String> currentUsername;

	public GoalsTool(GoalService goals, Supplier<String> currentUsername)
	{
		this.goals = goals;
		this.currentUsername = currentUsername;
	}

	@Override
	public String name()
	{
		return "manage_goals";
	}

	@Override
	public String description()
	{
		return "Track the player's long-term goals across sessions. Use action='add' to record a new "
			+ "goal (goal = the goal text, e.g. \"quest cape\"), action='list' to review current goals, "
			+ "or action='complete' to mark a goal done (goal = its id or a distinctive phrase). Set a "
			+ "goal whenever the player says they want to work toward something, and complete it when "
			+ "they achieve it.";
	}

	@Override
	public JsonObject inputSchema()
	{
		JsonObject action = new JsonObject();
		action.addProperty("type", "string");
		JsonArray enumValues = new JsonArray();
		enumValues.add("add");
		enumValues.add("list");
		enumValues.add("complete");
		action.add("enum", enumValues);
		action.addProperty("description", "add, list, or complete.");

		JsonObject goal = new JsonObject();
		goal.addProperty("type", "string");
		goal.addProperty("description", "The goal text (for add) or its id/phrase (for complete).");

		JsonObject properties = new JsonObject();
		properties.add("action", action);
		properties.add("goal", goal);

		JsonArray required = new JsonArray();
		required.add("action");

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		schema.addProperty("additionalProperties", false);
		return schema;
	}

	@Override
	public String execute(JsonObject input)
	{
		String username = currentUsername == null ? null : currentUsername.get();
		if (username == null || username.trim().isEmpty())
		{
			return "No player is logged in, so I can't manage goals right now.";
		}

		String action = optString(input, "action").toLowerCase();
		String goalText = optString(input, "goal");
		switch (action)
		{
			case "add":
				if (goalText.isEmpty())
				{
					return "No goal text was provided.";
				}
				return "Added goal: \"" + goals.add(username, goalText).getText() + "\".";
			case "complete":
			{
				Goal done = goals.complete(username, goalText);
				return done == null
					? "No matching active goal found for \"" + goalText + "\"."
					: "Marked goal done: \"" + done.getText() + "\". Nice work!";
			}
			case "list":
			default:
				return listGoals(username);
		}
	}

	private String listGoals(String username)
	{
		List<Goal> active = goals.active(username);
		if (active.isEmpty())
		{
			return "No active goals yet.";
		}
		StringBuilder sb = new StringBuilder("Active goals:\n");
		for (Goal g : active)
		{
			sb.append("- [").append(g.getId()).append("] ").append(g.getText()).append('\n');
		}
		return sb.toString();
	}

	private static String optString(JsonObject input, String key)
	{
		if (input == null || !input.has(key) || input.get(key).isJsonNull())
		{
			return "";
		}
		return input.get(key).getAsString().trim();
	}
}
