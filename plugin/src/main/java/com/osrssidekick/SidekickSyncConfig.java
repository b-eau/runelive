package com.osrssidekick;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(SidekickSyncConfig.GROUP)
public interface SidekickSyncConfig extends Config
{
	String GROUP = "osrs-sidekick-sync";

	@ConfigItem(
		keyName = "syncEnabled",
		name = "Enable syncing",
		description = "Send account updates (stats, quests, bank, kill counts) to your OSRS Sidekick dashboard",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 0
	)
	default boolean syncEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "backendUrl",
		name = "Sidekick server URL",
		description = "Base URL of your OSRS Sidekick web app",
		position = 1
	)
	default String backendUrl()
	{
		return "http://localhost:3000";
	}

	@ConfigItem(
		keyName = "linkAccount",
		name = "Link account",
		description = "While logged in to the game, tick this to connect the current character to your Sidekick dashboard — a browser window opens to finish sign-in. The box unticks itself. Requires syncing to be enabled.",
		position = 2
	)
	default boolean linkAccount()
	{
		return false;
	}
}
