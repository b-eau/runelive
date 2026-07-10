package com.osrssidekick;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SidekickSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SidekickSyncPlugin.class);
		RuneLite.main(args);
	}
}
