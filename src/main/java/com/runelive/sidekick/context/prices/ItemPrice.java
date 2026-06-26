package com.runelive.sidekick.context.prices;

import lombok.Value;

/** A Grand Exchange price for a single item, from the OSRS Wiki real-time prices API. */
@Value
public class ItemPrice
{
	int id;
	String name;
	int high;      // most recent instant-buy price (0 if unknown)
	int low;       // most recent instant-sell price (0 if unknown)
	long highTime; // unix seconds of the high (0 if unknown)
	long lowTime;  // unix seconds of the low (0 if unknown)

	/** A representative mid price: the average of buy/sell, or whichever side is known. */
	public int midPrice()
	{
		if (high > 0 && low > 0)
		{
			return (high + low) / 2;
		}
		return Math.max(high, low);
	}
}
