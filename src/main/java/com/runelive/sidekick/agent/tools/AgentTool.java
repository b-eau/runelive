package com.runelive.sidekick.agent.tools;

import com.google.gson.JsonObject;
import com.runelive.sidekick.llm.ToolSpec;

/** A tool the agent can call. Implementations are pure request/response and must not block long. */
public interface AgentTool
{
	String name();

	String description();

	JsonObject inputSchema();

	/**
	 * Executes the tool. Return human-/model-readable text. A thrown exception is turned into an
	 * error tool result by the agent loop, so throw for genuine failures and return text for
	 * "no result" style outcomes the model should reason about.
	 */
	String execute(JsonObject input);

	default ToolSpec toSpec()
	{
		return new ToolSpec(name(), description(), inputSchema());
	}
}
