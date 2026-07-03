package com.runelive.sidekick.agent;

import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.llm.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Holds the agent's tools and exposes their specs for the model and lookup for dispatch. */
public class ToolRegistry
{
	private final Map<String, AgentTool> byName = new LinkedHashMap<>();

	public ToolRegistry(List<AgentTool> tools)
	{
		for (AgentTool tool : tools)
		{
			byName.put(tool.name(), tool);
		}
	}

	public List<ToolSpec> specs()
	{
		List<ToolSpec> specs = new ArrayList<>();
		for (AgentTool tool : byName.values())
		{
			specs.add(tool.toSpec());
		}
		return specs;
	}

	/** Returns the tool with this name, or {@code null} if none is registered. */
	public AgentTool find(String name)
	{
		return byName.get(name);
	}
}
