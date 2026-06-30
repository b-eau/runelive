package com.runelive.sidekick.voice.realtime;

import com.google.gson.JsonObject;
import com.runelive.sidekick.SidekickPanel;
import com.runelive.sidekick.agent.SystemPrompts;
import com.runelive.sidekick.agent.ToolRegistry;
import com.runelive.sidekick.agent.tools.AgentTool;
import com.runelive.sidekick.context.PlayerContext;
import com.runelive.sidekick.context.PlayerContextSource;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.conversation.ConversationManager;
import com.runelive.sidekick.voice.AudioPlayer;
import com.runelive.sidekick.voice.StreamingMicrophone;
import com.runelive.sidekick.voice.VoiceBackend;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.sound.sampled.LineUnavailableException;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link VoiceBackend} for a realtime, audio-to-audio voice agent. On push-to-talk it streams the
 * microphone to a {@link RealtimeVoiceSession}; the agent's spoken reply streams back through
 * {@link AudioPlayer}. The agent's tools, persona, player context and conversation memory are the
 * same ones the text agent uses — they are handed to the session as instructions + tool specs, and
 * tool calls are executed against the shared {@link ToolRegistry}.
 *
 * <p>The session is opened lazily on the first key-press and kept alive for the mode's lifetime, so
 * only the first utterance pays the connection latency. All capture/network work happens off the
 * client thread (a control executor plus the session's own threads).
 */
@Slf4j
public class RealtimeVoiceBackend implements VoiceBackend, RealtimeSessionListener
{
	/** OpenAI-Realtime PCM is 24 kHz mono 16-bit; we capture and play at the same rate. */
	private static final int SAMPLE_RATE_HZ = 24_000;

	private static final String VOICE_RULES =
		"\n\nVOICE MODE: You are speaking out loud to the player. Reply in natural, concise spoken "
			+ "sentences — no markdown, bullet points, headings, code or URLs, and never read a link "
			+ "aloud. Keep answers short and conversational and offer to go deeper if they want more.";

	private final RealtimeVoiceSession session;
	private final ToolRegistry tools;
	private final PlayerContextSource contextSource;
	private final ConversationManager conversations;
	private final SidekickPanel panel;
	private final Runnable openPanel;
	private final Consumer<String> onToolStep;
	private final Consumer<String> status;
	private final String model;
	private final String voice;

	private final StreamingMicrophone microphone = new StreamingMicrophone(SAMPLE_RATE_HZ);
	private final AudioPlayer player = new AudioPlayer(SAMPLE_RATE_HZ);
	private final ExecutorService control = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "sidekick-realtime-control");
		t.setDaemon(true);
		return t;
	});

	private volatile boolean connecting;
	private volatile boolean connected;
	private volatile boolean talking;
	private volatile String username;
	private final StringBuilder assistantTurn = new StringBuilder();

	public RealtimeVoiceBackend(
		RealtimeVoiceSession session,
		ToolRegistry tools,
		PlayerContextSource contextSource,
		ConversationManager conversations,
		SidekickPanel panel,
		Runnable openPanel,
		Consumer<String> onToolStep,
		Consumer<String> status,
		String model,
		String voice)
	{
		this.session = session;
		this.tools = tools;
		this.contextSource = contextSource;
		this.conversations = conversations;
		this.panel = panel;
		this.openPanel = openPanel;
		this.onToolStep = onToolStep;
		this.status = status;
		this.model = model;
		this.voice = voice;
	}

	// ── VoiceBackend ───────────────────────────────────────────────────────────────────────────────

	@Override
	public void pressToTalk()
	{
		control.submit(() ->
		{
			talking = true;
			player.interrupt(); // barge-in: stop the assistant talking when the player speaks
			if (connected)
			{
				beginStreaming();
			}
			else if (!connecting)
			{
				connect();
			}
		});
	}

	@Override
	public void releaseToTalk()
	{
		control.submit(() ->
		{
			talking = false;
			microphone.stop();
			if (connected)
			{
				session.commitUserAudio();
				status.accept("Sidekick is thinking...");
			}
		});
	}

	@Override
	public void shutdown()
	{
		talking = false;
		microphone.stop();
		player.stop();
		session.close();
		control.shutdownNow();
	}

	// ── Connection / streaming ───────────────────────────────────────────────────────────────────

	private void connect()
	{
		PlayerContext context;
		try
		{
			context = contextSource.fetch(null);
		}
		catch (PlayerNotFoundException e)
		{
			talking = false;
			status.accept("<col=ff0000>No player logged in</col> — log in before using voice.");
			return;
		}
		username = context.getUsername();
		String memory = conversations.memoryBlock(username);
		String instructions = SystemPrompts.build(context, memory) + VOICE_RULES;

		try
		{
			player.start();
		}
		catch (LineUnavailableException e)
		{
			talking = false;
			status.accept("<col=ff0000>Speaker unavailable:</col> " + e.getMessage());
			return;
		}

		connecting = true;
		status.accept("<col=ffff00>Connecting voice agent...</col>");
		session.connect(RealtimeSessionConfig.builder()
			.model(model)
			.instructions(instructions)
			.tools(tools.specs())
			.voice(voice)
			.inputSampleRateHz(SAMPLE_RATE_HZ)
			.outputSampleRateHz(SAMPLE_RATE_HZ)
			.build(), this);
	}

	private void beginStreaming()
	{
		try
		{
			microphone.start(session::sendAudio);
			status.accept("<col=ffff00>Listening...</col> (release key when done)");
		}
		catch (LineUnavailableException e)
		{
			talking = false;
			status.accept("<col=ff0000>Microphone unavailable:</col> " + e.getMessage());
		}
	}

	// ── RealtimeSessionListener (network thread) ───────────────────────────────────────────────────

	@Override
	public void onConnected()
	{
		connecting = false;
		connected = true;
		// If the key is still held, start streaming now that the session is configured.
		control.submit(() ->
		{
			if (talking)
			{
				beginStreaming();
			}
		});
	}

	@Override
	public void onUserTranscript(String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			return;
		}
		assistantTurn.setLength(0);
		conversations.recordUser(username, text);
		status.accept("<col=ffffff>You:</col> " + text);
		if (panel != null)
		{
			panel.showPending(text);
		}
		openPanel.run();
	}

	@Override
	public void onAssistantText(String text)
	{
		if (text != null && !text.isEmpty())
		{
			assistantTurn.setLength(0);
			assistantTurn.append(text);
		}
	}

	@Override
	public void onAssistantAudio(byte[] pcm16)
	{
		player.enqueue(pcm16);
	}

	@Override
	public void onResponseDone()
	{
		String reply = assistantTurn.toString().trim();
		if (!reply.isEmpty())
		{
			conversations.recordAssistant(reply);
			if (panel != null)
			{
				panel.showConversation(conversations.current());
			}
		}
	}

	@Override
	public String onToolCall(String name, JsonObject arguments)
	{
		onToolStep.accept(describeTool(name));
		AgentTool tool = tools.find(name);
		if (tool == null)
		{
			return "Unknown tool: " + name;
		}
		try
		{
			return tool.execute(arguments);
		}
		catch (RuntimeException e)
		{
			return "Tool error: " + e.getMessage();
		}
	}

	@Override
	public void onError(String message)
	{
		status.accept("<col=ff0000>Voice error:</col> " + message);
	}

	@Override
	public void onClosed()
	{
		connected = false;
		connecting = false;
	}

	private static String describeTool(String name)
	{
		return name == null ? "" : name.replace('_', ' ');
	}
}
