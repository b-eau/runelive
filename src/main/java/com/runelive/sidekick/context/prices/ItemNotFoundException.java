package com.runelive.sidekick.context.prices;

/** Thrown when an item name cannot be resolved to a Grand Exchange item. */
public class ItemNotFoundException extends RuntimeException
{
	public ItemNotFoundException(String item)
	{
		super("No Grand Exchange item matches: " + item);
	}
}
