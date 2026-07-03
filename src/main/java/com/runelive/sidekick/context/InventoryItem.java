package com.runelive.sidekick.context;

import lombok.Value;

/** A single item stack in the player's inventory. */
@Value
public class InventoryItem
{
	String name;
	int quantity;
	boolean noted;
}
