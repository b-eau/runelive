package com.runelive.sidekick.context;

/** Thrown when a requested player cannot be found on the backing data source. */
public class PlayerNotFoundException extends RuntimeException
{
	public PlayerNotFoundException(String username)
	{
		super("Player not found: " + username);
	}
}
