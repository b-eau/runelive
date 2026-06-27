package com.runelive.sidekick.agent;

import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.llm.ContentPart;
import com.runelive.sidekick.llm.LlmClient;
import com.runelive.sidekick.llm.LlmMessage;
import com.runelive.sidekick.llm.LlmRequest;
import com.runelive.sidekick.llm.LlmResult;
import com.runelive.sidekick.llm.Modality;
import com.runelive.sidekick.llm.StopReason;
import com.runelive.sidekick.llm.TextPart;
import com.runelive.sidekick.llm.ToolCall;
import com.runelive.sidekick.llm.ToolResult;
import com.runelive.sidekick.llm.ToolSpec;
import com.runelive.sidekick.llm.ToolUsePart;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates one assistant turn: builds the personalised, modality-aware system prompt, then runs
 * the agentic tool loop (model → tool calls → results → model …) until the model produces a final
 * answer. Provider details live behind {@link LlmClient}; tool execution behind {@link ToolRegistry}.
 */
@Slf4j
public class AgentService
{
	private final LlmClient llm;
	private final ToolRegistry tools;
	private final int maxSteps;

	public AgentService(LlmClient llm, ToolRegistry tools, int maxSteps)
	{
		this.llm = llm;
		this.tools = tools;
		this.maxSteps = maxSteps;
	}

	/**
	 * Produces a reply to the latest user message.
	 *
	 * @param context the player's account snapshot, injected into the system prompt
	 * @param modality TEXT or VOICE — tunes the output style
	 * @param history the prior conversation as user/assistant text turns, ending with the latest user turn
	 * @param onToolCall called with a short human-readable description before each tool execution;
	 *                   may be {@code null} if the caller doesn't need progress feedback
	 */
	public AgentReply chat(PlayerContext context, Modality modality, List<LlmMessage> history,
		Consumer<String> onToolCall)
	{
		String system = SystemPrompts.build(context, modality);
		List<ToolSpec> specs = tools.specs();
		List<LlmMessage> messages = new ArrayList<>(history);
		List<ToolInvocation> invocations = new ArrayList<>();
		int inputTokens = 0;
		int outputTokens = 0;

		for (int step = 0; step < maxSteps; step++)
		{
			LlmResult result = llm.complete(new LlmRequest(system, messages, specs));
			inputTokens += result.getInputTokens();
			outputTokens += result.getOutputTokens();

			if (result.wantsTools())
			{
				// Reconstruct the assistant turn (text + tool calls) in provider-neutral form,
				// preserving any opaque signature, then answer every call in one tool-result turn.
				List<ContentPart> assistantParts = new ArrayList<>();
				if (!result.getText().isEmpty())
				{
					assistantParts.add(new TextPart(result.getText()));
				}
				for (ToolCall call : result.getToolCalls())
				{
					assistantParts.add(new ToolUsePart(call.getId(), call.getName(), call.getInput(), call.getSignature()));
				}
				messages.add(LlmMessage.assistant(assistantParts));

				List<ToolResult> toolResults = new ArrayList<>();
				for (ToolCall call : result.getToolCalls())
				{
					if (onToolCall != null)
					{
						onToolCall.accept(describeToolCall(call));
					}
					ToolOutcome outcome = runTool(call);
					invocations.add(new ToolInvocation(call.getName(), call.getInput().toString(), outcome.output, outcome.error));
					toolResults.add(new ToolResult(call.getId(), call.getName(), outcome.output, outcome.error));
				}
				messages.add(LlmMessage.toolResults(toolResults));
				continue;
			}

			String text = result.getText();
			if (text.isEmpty())
			{
				text = result.getStopReason() == StopReason.REFUSAL
					? "Sorry, I can't help with that one."
					: "I'm not sure how to answer that — could you rephrase?";
			}
			return new AgentReply(text, invocations, result.getStopReason(), inputTokens, outputTokens);
		}

		log.debug("Agent hit the {}-step tool limit", maxSteps);
		return new AgentReply(
			"That turned into a lot of lookups — let's narrow it down. What's the single most important thing you want to know?",
			invocations, StopReason.OTHER, inputTokens, outputTokens);
	}

	private ToolOutcome runTool(ToolCall call)
	{
		AgentTool tool = tools.find(call.getName());
		if (tool == null)
		{
			return new ToolOutcome("Unknown tool: " + call.getName(), true);
		}
		try
		{
			return new ToolOutcome(tool.execute(call.getInput()), false);
		}
		catch (RuntimeException e)
		{
			log.debug("Tool {} failed", call.getName(), e);
			return new ToolOutcome("Tool error: " + e.getMessage(), true);
		}
	}

	private static String describeToolCall(ToolCall call)
	{
		switch (call.getName())
		{
			case "search_osrs_wiki":
			{
				String query = call.getInput().has("query")
					? call.getInput().get("query").getAsString() : "...";
				return "Searching wiki: " + query;
			}
			case "get_grand_exchange_price":
			{
				String item = call.getInput().has("item_name")
					? call.getInput().get("item_name").getAsString() : "...";
				return "GE price: " + item;
			}
			default:
				return call.getName().replace('_', ' ');
		}
	}

	private static final class ToolOutcome
	{
		final String output;
		final boolean error;

		ToolOutcome(String output, boolean error)
		{
			this.output = output;
			this.error = error;
		}
	}
}
