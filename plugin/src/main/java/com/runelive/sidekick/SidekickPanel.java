package com.runelive.sidekick;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel that renders the AI response as formatted HTML.
 *
 * <p>Status messages (listening, transcribing, tool lookups) remain in game chat so the player
 * can follow progress without leaving the game view. The final answer lands here as rich text and
 * the panel is brought into focus automatically.
 */
public class SidekickPanel extends PluginPanel
{
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
			+ "Hold the push-to-talk key, speak your question, then release."
			+ "</p>";

	private final JLabel queryLabel;
	private final JEditorPane responsePane;

	public SidekickPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Query shown at top so the player remembers what they asked
		queryLabel = new JLabel(" ");
		queryLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		queryLabel.setFont(queryLabel.getFont().deriveFont(Font.ITALIC, 11f));
		queryLabel.setOpaque(true);
		queryLabel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		queryLabel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		add(queryLabel, BorderLayout.NORTH);

		responsePane = new JEditorPane("text/html", wrap(PLACEHOLDER));
		responsePane.setEditable(false);
		responsePane.setBackground(BG);
		responsePane.setBorder(null);

		JScrollPane scroll = new JScrollPane(responsePane);
		scroll.setBorder(null);
		scroll.setBackground(BG);
		scroll.getViewport().setBackground(BG);
		add(scroll, BorderLayout.CENTER);
	}

	/**
	 * Renders a new response. Safe to call from any thread; marshals to the EDT internally.
	 *
	 * @param query the player's spoken question (displayed above the response)
	 * @param markdownText the agent's response in Markdown
	 */
	public void showResponse(String query, String markdownText)
	{
		String html = wrap(MarkdownUtil.toHtml(markdownText));
		String label = query.length() > 60 ? query.substring(0, 57) + "…" : query;
		SwingUtilities.invokeLater(() ->
		{
			queryLabel.setText(label);
			responsePane.setText(html);
			responsePane.setCaretPosition(0);
		});
	}

	private static String wrap(String body)
	{
		return "<html><head><style>" + CSS + "</style></head><body>" + body + "</body></html>";
	}
}
