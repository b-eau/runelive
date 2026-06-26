package com.runelive.sidekick.agent.tools;

import com.google.gson.JsonObject;
import com.runelive.sidekick.context.prices.ItemNotFoundException;
import com.runelive.sidekick.context.prices.ItemPrice;
import com.runelive.sidekick.context.prices.PriceClient;

/** Tool: look up a live Grand Exchange price so the agent's cost figures are real, not guessed. */
public class GrandExchangePriceTool implements AgentTool
{
	private final PriceClient priceClient;

	public GrandExchangePriceTool(PriceClient priceClient)
	{
		this.priceClient = priceClient;
	}

	@Override
	public String name()
	{
		return "get_grand_exchange_price";
	}

	@Override
	public String description()
	{
		return "Look up the current Grand Exchange price of an Old School RuneScape item by name. "
			+ "Call this whenever you mention buying, selling, or the cost/value of an item, so your "
			+ "gp figures are accurate and current rather than estimated. Item names must match the "
			+ "in-game name (e.g. 'Abyssal whip', 'Dragon scimitar', 'Prayer potion(4)').";
	}

	@Override
	public JsonObject inputSchema()
	{
		return Schemas.singleString("item", "Exact in-game item name to price.");
	}

	@Override
	public String execute(JsonObject input)
	{
		String item = Schemas.optString(input, "item").trim();
		if (item.isEmpty())
		{
			return "No item name was provided.";
		}
		try
		{
			ItemPrice price = priceClient.priceByName(item);
			if (price.midPrice() <= 0)
			{
				return price.getName() + " has no recent Grand Exchange trade data.";
			}
			return String.format(
				"%s (item id %d): buy %s gp, sell %s gp, mid ~%s gp.",
				price.getName(),
				price.getId(),
				gp(price.getHigh()),
				gp(price.getLow()),
				gp(price.midPrice()));
		}
		catch (ItemNotFoundException e)
		{
			return "No Grand Exchange item is named \"" + item + "\". Check the exact in-game spelling.";
		}
	}

	private static String gp(int value)
	{
		return value <= 0 ? "n/a" : String.format("%,d", value);
	}
}
