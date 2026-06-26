package com.runelive.sidekick.testutil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runelive.sidekick.llm.LlmClient;
import com.runelive.sidekick.llm.LlmRequest;
import com.runelive.sidekick.llm.LlmResult;
import com.runelive.sidekick.llm.StopReason;
import com.runelive.sidekick.llm.ToolCall;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A scriptable {@link LlmClient} for exercising the agent loop without a real model. */
public final class FakeLlmClient implements LlmClient
{
	private final Deque<LlmResult> scripted = new ArrayDeque<>();
	public final List<LlmRequest> requests = new ArrayList<>();

	public FakeLlmClient script(LlmResult result)
	{
		scripted.add(result);
		return this;
	}

	@Override
	public LlmResult complete(LlmRequest request)
	{
		requests.add(request);
		return scripted.isEmpty() ? endTurn("(no scripted response)") : scripted.poll();
	}

	public static LlmResult endTurn(String text)
	{
		return new LlmResult(StopReason.END_TURN, text, List.of(), new JsonArray(), 0, 0);
	}

	public static LlmResult toolUse(String id, String name, JsonObject input)
	{
		JsonArray content = new JsonArray();
		JsonObject block = new JsonObject();
		block.addProperty("type", "tool_use");
		block.addProperty("id", id);
		block.addProperty("name", name);
		block.add("input", input);
		content.add(block);
		return new LlmResult(StopReason.TOOL_USE, "", List.of(new ToolCall(id, name, input)), content, 0, 0);
	}
}
