package com.runelive.sidekick;

import com.runelive.sidekick.conversation.Conversation;
import com.runelive.sidekick.conversation.ConversationManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel: a scrolling conversation transcript with an inline follow-up input, plus a
 * navigator for browsing and reopening past conversations.
 *
 * <p>The panel renders the active thread as HTML; assistant turns are Markdown, player turns are
 * plain text. All public mutators marshal to the EDT, so the plugin may call them from its query
 * thread. UI actions are reported back through {@link Listener}, which the plugin implements.
 */
public class SidekickPanel extends PluginPanel
{
	/** Callbacks from the panel into the plugin. */
	public interface Listener
	{
		/** The player asked something (typed a follow-up in the panel). */
		void onSend(String text);

		/** Start a fresh conversation. */
		void onNewConversation();

		/** The player opened the history navigator; the plugin should load and supply the list. */
		void onHistoryRequested();

		/** The player picked a past conversation to reopen. */
		void onConversationSelected(String id);

		/** The panel became visible — a chance to proactively review (throttled by the plugin). */
		void onPanelShown();
	}

	/** One-tap prompts. Each entry is {button label, prompt sent as if typed}. */
	private static final String[][] QUICK_ACTIONS = {
		{"What next?", "What should I do next right now, given my account and what I'm currently doing?"},
		{"Review setup", REVIEW_PROMPT_CONST()},
		{"Money maker", "What's a good money-making method for me right now given my stats, gear and "
			+ "the current prices? Give one or two options with rough gp/hr."},
		{"My goals", "How am I progressing toward my current goals, and what should I do next for them?"},
	};

	/** Shared so the plugin's auto-review uses the exact same prompt as the button. */
	public static String reviewPrompt()
	{
		return REVIEW_PROMPT_CONST();
	}

	private static String REVIEW_PROMPT_CONST()
	{
		return "Review what I'm doing right now and my overall account, and suggest the highest-impact "
			+ "improvements I could realistically get soon — better tools or gear, XP boosts (diaries, "
			+ "outfits, relics), efficiency unlocks, or a better method. Be specific and concrete, and "
			+ "explain why each is worth it for me.";
	}

	private static final Color BG = new Color(0x2b, 0x2b, 0x2b);
	private static final Color BORDER_COLOR = new Color(0x44, 0x44, 0x44);

	private static final String CSS =
		"body{background-color:#2b2b2b;color:#c8c8c8;font-family:sans-serif;font-size:11px;"
			+ "margin:6px 8px;padding:0;line-height:1.4}"
			+ "h1{color:#ff981f;font-size:14px;margin-top:8px;margin-bottom:2px}"
			+ "h2{color:#ff981f;font-size:13px;margin-top:8px;margin-bottom:2px}"
			+ "h3{color:#ff981f;font-size:12px;margin-top:6px;margin-bottom:2px}"
			+ "b{color:#ffffff}"
			+ "strong{color:#ffffff}"
			+ "code{color:#e6db74}"
			+ "ul{margin-top:2px;margin-bottom:2px;padding-left:16px}"
			+ "ol{margin-top:2px;margin-bottom:2px;padding-left:16px}"
			+ "li{margin-top:1px;margin-bottom:1px}"
			+ "p{margin-top:3px;margin-bottom:3px}"
			+ "hr{border-top:1px solid #555555}"
			+ "a{color:#5b9bd5}";

	private static final String PLACEHOLDER =
		"<p style='color:#666666;text-align:center;margin-top:24px;'>"
			+ "Ask below, type <b style='color:#888888'>::sk your question</b> in chat,"
			+ " or hold the push-to-talk key and speak."
			+ "</p>";

	private final JEditorPane transcript;
	private final JTextField input;
	private final JButton sendButton;
	private final JPanel cards;
	private final java.awt.CardLayout cardLayout;
	private final DefaultListModel<Conversation> historyModel;
	private JList<Conversation> historyList;

	/** Most recent tool-call progress lines shown under the "thinking" indicator. */
	private static final int MAX_PROGRESS = 8;

	// Render state for the active thread (mutated only on the EDT).
	private List<Conversation.Turn> turns = new ArrayList<>();
	private final List<String> progress = new ArrayList<>();
	private String pendingUser;
	private boolean thinking;

	private final JLabel goalsLabel = new JLabel();
	private Listener listener;

	public SidekickPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		north.add(buildToolbar());
		north.add(buildQuickActions());
		configureGoalsLabel();
		north.add(goalsLabel);
		add(north, BorderLayout.NORTH);

		// Proactively review when the panel becomes visible (the plugin throttles / gates it).
		addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
				&& isShowing() && listener != null)
			{
				listener.onPanelShown();
			}
		});

		cardLayout = new java.awt.CardLayout();
		cards = new JPanel(cardLayout);
		cards.setBackground(BG);

		// Force the HTML view to track the (fixed) sidebar width so content wraps instead of
		// producing a horizontal scrollbar, which is impractical to use mid-game.
		transcript = new JEditorPane("text/html", wrap(PLACEHOLDER))
		{
			@Override
			public boolean getScrollableTracksViewportWidth()
			{
				return true;
			}
		};
		transcript.setEditable(false);
		transcript.setBackground(BG);
		transcript.setBorder(null);

		input = new JTextField();
		input.setToolTipText("Ask the sidekick a follow-up…");
		input.addActionListener(e -> doSend());

		sendButton = new JButton("Ask");
		sendButton.addActionListener(e -> doSend());

		historyModel = new DefaultListModel<>();

		cards.add(buildChatCard(), "chat");
		cards.add(buildHistoryCard(), "history");
		add(cards, BorderLayout.CENTER);

		cardLayout.show(cards, "chat");
	}

	public void setListener(Listener listener)
	{
		this.listener = listener;
	}

	// ── Public API (thread-safe) ───────────────────────────────────────────────────────────────────

	/** Shows the player's message immediately with a "thinking" indicator while the agent runs. */
	public void showPending(String userText)
	{
		SwingUtilities.invokeLater(() ->
		{
			pendingUser = userText;
			progress.clear();
			thinking = true;
			setBusy(true);
			renderTranscript();
			cardLayout.show(cards, "chat");
		});
	}

	/** Appends a live tool-call step under the "thinking" indicator (parity with game chat). */
	public void showProgress(String step)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (!thinking)
			{
				return;
			}
			progress.add(step);
			while (progress.size() > MAX_PROGRESS)
			{
				progress.remove(0);
			}
			renderTranscript();
		});
	}

	/** Replaces the transcript with the (now complete) active conversation. */
	public void showConversation(Conversation conversation)
	{
		List<Conversation.Turn> snapshot = conversation == null
			? new ArrayList<>() : new ArrayList<>(conversation.getTurns());
		SwingUtilities.invokeLater(() ->
		{
			turns = snapshot;
			pendingUser = null;
			thinking = false;
			setBusy(false);
			renderTranscript();
			cardLayout.show(cards, "chat");
		});
	}

	/** Surfaces an error in the transcript without losing the conversation. */
	public void showError(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			pendingUser = null;
			thinking = false;
			setBusy(false);
			renderTranscript("<p style='color:#ff5555;margin-top:6px'>" + esc(message) + "</p>");
			cardLayout.show(cards, "chat");
		});
	}

	/** Populates and shows the history navigator. */
	public void showHistory(List<Conversation> conversations)
	{
		SwingUtilities.invokeLater(() ->
		{
			historyModel.clear();
			for (Conversation c : conversations)
			{
				historyModel.addElement(c);
			}
			cardLayout.show(cards, "history");
		});
	}

	/** Shows the player's active goals as a compact strip, or hides it when there are none. */
	public void showGoals(List<String> goals)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (goals == null || goals.isEmpty())
			{
				goalsLabel.setVisible(false);
				return;
			}
			StringBuilder sb = new StringBuilder("<html><body style='width:190px'>"
				+ "<b style='color:#ff981f'>Goals</b>");
			for (String g : goals)
			{
				sb.append("<br>• ").append(esc(g));
			}
			sb.append("</body></html>");
			goalsLabel.setText(sb.toString());
			goalsLabel.setVisible(true);
		});
	}

	// ── UI construction ────────────────────────────────────────────────────────────────────────────

	private JPanel buildToolbar()
	{
		JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		toolbar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));

		JButton newButton = new JButton("+ New");
		newButton.setToolTipText("Start a new conversation");
		newButton.addActionListener(e ->
		{
			turns = new ArrayList<>();
			pendingUser = null;
			thinking = false;
			setBusy(false);
			renderTranscript();
			cardLayout.show(cards, "chat");
			if (listener != null)
			{
				listener.onNewConversation();
			}
		});

		JButton historyButton = new JButton("History");
		historyButton.setToolTipText("Browse past conversations");
		historyButton.addActionListener(e ->
		{
			if (listener != null)
			{
				listener.onHistoryRequested();
			}
		});

		toolbar.add(newButton);
		toolbar.add(historyButton);
		return toolbar;
	}

	/** A wrapping row of one-tap prompts (incl. the proactive "Review setup"). */
	private JPanel buildQuickActions()
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));
		for (String[] action : QUICK_ACTIONS)
		{
			String label = action[0];
			String prompt = action[1];
			JButton button = new JButton(label);
			button.setMargin(new java.awt.Insets(1, 6, 1, 6));
			button.setFont(button.getFont().deriveFont(10f));
			button.addActionListener(e -> submitQuickAction(prompt));
			row.add(button);
		}
		return row;
	}

	private void configureGoalsLabel()
	{
		goalsLabel.setOpaque(true);
		goalsLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		goalsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		goalsLabel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR),
			BorderFactory.createEmptyBorder(4, 8, 4, 8)));
		goalsLabel.setVisible(false);
	}

	/** A quick-action button behaves exactly like the player typing that prompt. */
	private void submitQuickAction(String prompt)
	{
		if (listener == null || thinking)
		{
			return;
		}
		showPending(prompt);
		listener.onSend(prompt);
	}

	private JPanel buildChatCard()
	{
		JPanel chat = new JPanel(new BorderLayout());
		chat.setBackground(BG);

		JScrollPane scroll = new JScrollPane(transcript);
		scroll.setBorder(null);
		scroll.setBackground(BG);
		scroll.getViewport().setBackground(BG);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		chat.add(scroll, BorderLayout.CENTER);

		JPanel inputRow = new JPanel(new BorderLayout(4, 0));
		inputRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		inputRow.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		inputRow.add(input, BorderLayout.CENTER);
		inputRow.add(sendButton, BorderLayout.EAST);
		chat.add(inputRow, BorderLayout.SOUTH);
		return chat;
	}

	private JPanel buildHistoryCard()
	{
		JPanel history = new JPanel(new BorderLayout());
		history.setBackground(BG);

		JButton back = new JButton("← Back");
		back.addActionListener(e -> cardLayout.show(cards, "chat"));
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		top.setBackground(BG);
		top.add(back);
		history.add(top, BorderLayout.NORTH);

		historyList = new JList<>(historyModel);
		historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		historyList.setBackground(BG);
		historyList.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		historyList.setCellRenderer(new ConversationCellRenderer());
		historyList.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting())
			{
				return;
			}
			Conversation selected = historyList.getSelectedValue();
			if (selected != null && listener != null)
			{
				listener.onConversationSelected(selected.getId());
			}
		});

		JScrollPane scroll = new JScrollPane(historyList);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(BG);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		history.add(scroll, BorderLayout.CENTER);
		return history;
	}

	// ── Rendering ──────────────────────────────────────────────────────────────────────────────────

	private void doSend()
	{
		String text = input.getText().trim();
		if (text.isEmpty() || listener == null || thinking)
		{
			return;
		}
		input.setText("");
		showPending(text);
		listener.onSend(text);
	}

	private void setBusy(boolean busy)
	{
		input.setEnabled(!busy);
		sendButton.setEnabled(!busy);
		sendButton.setText(busy ? "…" : "Ask");
	}

	private void renderTranscript()
	{
		renderTranscript(null);
	}

	private void renderTranscript(String trailingHtml)
	{
		StringBuilder body = new StringBuilder();
		boolean any = false;
		for (Conversation.Turn turn : turns)
		{
			body.append(turnHtml(turn.isUser(), turn.getText(), any));
			any = true;
		}
		if (pendingUser != null)
		{
			body.append(turnHtml(true, pendingUser, any));
			any = true;
		}
		if (thinking)
		{
			body.append("<p style='color:#888888;margin-top:4px'><i>Sidekick is thinking…</i></p>");
			for (String step : progress)
			{
				body.append("<p style='color:#777777;margin:0 0 0 8px'>• ").append(esc(step)).append("</p>");
			}
		}
		if (trailingHtml != null)
		{
			body.append(trailingHtml);
		}

		String html = wrap(any || trailingHtml != null ? body.toString() : PLACEHOLDER);
		transcript.setText(html);
		// Pin to the newest content.
		transcript.setCaretPosition(transcript.getDocument().getLength());
	}

	private static String turnHtml(boolean user, String text, boolean precededByContent)
	{
		StringBuilder sb = new StringBuilder();
		if (precededByContent)
		{
			sb.append("<hr>");
		}
		if (user)
		{
			sb.append("<p style='margin-top:4px'><b style='color:#7ab8ff'>You:</b> ")
				.append(esc(text == null ? "" : text).replace("\n", "<br>"))
				.append("</p>");
		}
		else
		{
			sb.append("<p style='margin-top:4px'><b style='color:#ff981f'>Sidekick</b></p>")
				.append(MarkdownUtil.toHtml(text == null ? "" : text));
		}
		return sb.toString();
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String wrap(String body)
	{
		return "<html><head><style>" + CSS + "</style></head><body>" + body + "</body></html>";
	}

	/** Renders each past conversation as its title plus a muted "x ago" line. */
	private static final class ConversationCellRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (value instanceof Conversation)
			{
				Conversation c = (Conversation) value;
				String when = ConversationManager.relativeTime(c.getUpdatedAt(), System.currentTimeMillis());
				setText("<html><body style='width:150px'><b>" + esc(c.getTitle()) + "</b>"
					+ "<br><font color='#888888' size='2'>" + esc(when) + "</font></body></html>");
			}
			setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
			if (!isSelected)
			{
				setBackground(BG);
				setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}
			return this;
		}
	}
}
