package com.osrssidekick;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

class SidekickPanel extends PluginPanel
{
	private final SidekickSyncPlugin plugin;
	private final JLabel titleLabel = new JLabel("OSRS Sidekick", SwingConstants.CENTER);
	private final JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
	private final JButton linkButton = new JButton("Link account");
	private final JButton dashboardButton = new JButton("Open dashboard");

	SidekickPanel(SidekickSyncPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		titleLabel.setFont(titleLabel.getFont().deriveFont(16f));
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		linkButton.setAlignmentX(Component.CENTER_ALIGNMENT);
		linkButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		linkButton.addActionListener(e -> plugin.beginLinking());

		dashboardButton.setAlignmentX(Component.CENTER_ALIGNMENT);
		dashboardButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		dashboardButton.addActionListener(e -> plugin.openDashboard());

		content.add(Box.createVerticalStrut(12));
		content.add(titleLabel);
		content.add(Box.createVerticalStrut(10));
		content.add(statusLabel);
		content.add(Box.createVerticalStrut(16));
		content.add(linkButton);
		content.add(Box.createVerticalStrut(8));
		content.add(dashboardButton);

		add(content, BorderLayout.NORTH);
		refresh();
	}

	void setStatus(String text)
	{
		SwingUtilities.invokeLater(() ->
			statusLabel.setText("<html><div style='text-align:center'>" + text.replace("\n", "<br>") + "</div></html>"));
	}

	void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			String name = plugin.currentDisplayName();
			if (!plugin.isSyncEnabled())
			{
				setStatus("Syncing is disabled.\nEnable it in the plugin settings.");
			}
			else if (!plugin.isLinked())
			{
				setStatus(name == null
					? "Log in to the game, then link your account."
					: "Not linked yet. Click Link account to connect " + name + ".");
			}
			else
			{
				setStatus("Linked" + (name != null ? " as " + name : "") + " — syncing is active.");
			}
			linkButton.setEnabled(!plugin.isLinked());
		});
	}
}
