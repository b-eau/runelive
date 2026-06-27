package com.runelive.sidekick.web;

import com.runelive.sidekick.agent.AgentReply;
import com.runelive.sidekick.agent.AgentService;
import com.runelive.sidekick.agent.ToolInvocation;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerContextSource;
import com.runelive.sidekick.llm.LlmMessage;
import com.runelive.sidekick.llm.Modality;
import com.runelive.sidekick.llm.Role;
import java.util.ArrayList;
import java.util.List;

/**
 * Application logic for a chat request, independent of the HTTP transport so it can be unit and
 * end-to-end tested directly: resolve the player's context, convert the transcript into model
 * messages, run the agent, and shape the response.
 */
public class ChatService
{
	private final PlayerContextSource contextSource;
	private final AgentService agentService;
	private final String defaultPlayer;

	public ChatService(PlayerContextSource contextSource, AgentService agentService, String defaultPlayer)
	{
		this.contextSource = contextSource;
		this.agentService = agentService;
		this.defaultPlayer = defaultPlayer;
	}

	public ChatDtos.ChatResponse handle(ChatDtos.ChatRequest request)
	{
		String player = firstNonBlank(request == null ? null : request.player, defaultPlayer);
		if (isBlank(player))
		{
			return ChatDtos.ChatResponse.error("No player set. Enter your OSRS username so I can tailor advice to your account.");
		}
		if (request == null || request.messages == null || request.messages.isEmpty())
		{
			return ChatDtos.ChatResponse.error("No message to respond to.");
		}

		List<LlmMessage> history = toHistory(request.messages);
		if (history.isEmpty() || history.get(history.size() - 1).getRole() != Role.USER)
		{
			return ChatDtos.ChatResponse.error("The conversation must end with a user message.");
		}

		Modality modality = Modality.fromString(request.modality);
		PlayerContext context = contextSource.fetch(player);
		AgentReply reply = agentService.chat(context, modality, history, null);

		return toResponse(reply, modality, context);
	}

	private static List<LlmMessage> toHistory(List<ChatDtos.Turn> turns)
	{
		List<LlmMessage> history = new ArrayList<>();
		for (ChatDtos.Turn turn : turns)
		{
			if (turn == null || turn.content == null || turn.content.trim().isEmpty())
			{
				continue;
			}
			if ("assistant".equalsIgnoreCase(turn.role))
			{
				history.add(LlmMessage.assistantText(turn.content));
			}
			else
			{
				history.add(LlmMessage.userText(turn.content));
			}
		}
		return history;
	}

	private ChatDtos.ChatResponse toResponse(AgentReply reply, Modality modality, PlayerContext context)
	{
		ChatDtos.ChatResponse response = new ChatDtos.ChatResponse();
		response.reply = reply.getText();
		response.modality = modality == Modality.VOICE ? "voice" : "text";
		response.player = context.getUsername();

		ChatDtos.ContextSummary summary = new ChatDtos.ContextSummary();
		summary.username = context.getUsername();
		summary.accountType = context.getAccountType();
		summary.combatLevel = context.getCombatLevel();
		summary.totalLevel = context.getTotalLevel();
		summary.ironman = context.isIronman();
		response.context = summary;

		List<ChatDtos.ToolCallDto> tools = new ArrayList<>();
		for (ToolInvocation invocation : reply.getToolInvocations())
		{
			ChatDtos.ToolCallDto dto = new ChatDtos.ToolCallDto();
			dto.name = invocation.getName();
			dto.input = invocation.getInput();
			dto.output = invocation.getOutput();
			dto.error = invocation.isError();
			tools.add(dto);
		}
		response.tools = tools;

		ChatDtos.Usage usage = new ChatDtos.Usage();
		usage.inputTokens = reply.getInputTokens();
		usage.outputTokens = reply.getOutputTokens();
		response.usage = usage;
		return response;
	}

	private static String firstNonBlank(String a, String b)
	{
		return !isBlank(a) ? a.trim() : (b == null ? null : b.trim());
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.trim().isEmpty();
	}
}
