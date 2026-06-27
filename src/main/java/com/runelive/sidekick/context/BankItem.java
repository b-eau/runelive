package com.runelive.sidekick.context;

import lombok.Value;

/** A single stack of items in the player's bank. */
@Value
public class BankItem
{
	String name;
	int quantity;
}
