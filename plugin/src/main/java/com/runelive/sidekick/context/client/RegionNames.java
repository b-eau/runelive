package com.runelive.sidekick.context.client;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * Maps OSRS region IDs (from {@link WorldPoint#getRegionID()}) to human-readable area names.
 *
 * <p>Coverage is intentional: only named, frequently-visited areas are included. Unknown regions
 * fall back to a coordinate-based description rather than a misleading name.
 */
final class RegionNames
{
	private static final Map<Integer, String> REGIONS = new HashMap<>();

	static
	{
		// Lumbridge & Draynor
		add(12850, "Lumbridge");
		add(12849, "Lumbridge");
		add(12338, "Draynor Village");
		add(12339, "Draynor Village");

		// Varrock
		add(12596, "Varrock");
		add(12597, "Varrock");
		add(12852, "Varrock");
		add(12853, "Varrock");
		add(12598, "Grand Exchange");

		// Falador & Barbarian Village
		add(11826, "Falador");
		add(11827, "Falador");
		add(11828, "Falador");
		add(12341, "Barbarian Village");

		// Edgeville & Wilderness border
		add(12342, "Edgeville");
		add(12343, "Edgeville");

		// Al Kharid & Shantay Pass
		add(13105, "Al Kharid");
		add(13106, "Al Kharid");

		// Burthorpe & Taverley
		add(11319, "Burthorpe");
		add(11320, "Taverley");
		add(11575, "Taverley Dungeon");

		// Catherby & Camelot
		add(11062, "Camelot / Seers' Village");
		add(10806, "Camelot / Seers' Village");
		add(11062, "Catherby");

		// Ardougne
		add(10547, "East Ardougne");
		add(10548, "East Ardougne");
		add(10291, "West Ardougne");

		// Yanille & Watchtower
		add(10288, "Yanille");
		add(10289, "Yanille");

		// Gnome Stronghold
		add(9781, "Tree Gnome Stronghold");
		add(9782, "Tree Gnome Stronghold");

		// Kandarin
		add(10546, "Fishing Guild");
		add(10545, "Hemenster / Fishing Guild area");

		// Morytania
		add(13622, "Canifis");
		add(13623, "Canifis");
		add(13878, "Slayer Tower");
		add(14390, "Barrows");
		add(14391, "Barrows");
		add(13622, "Canifis");

		// Meiyerditch / Darkmeyer
		add(14388, "Meiyerditch");
		add(14644, "Darkmeyer");

		// Desert
		add(13361, "Pollnivneach");
		add(13362, "Pollnivneach");
		add(13105, "Duel Arena / Al Kharid");
		add(13357, "Nardah");
		add(12590, "Pyramid / Sophanem area");

		// Karamja
		add(11311, "Karamja / Brimhaven");
		add(11566, "TzHaar City");
		add(11567, "TzHaar City");

		// Feldip Hills
		add(9772, "Feldip Hills");
		add(9776, "Castle Wars");

		// Ape Atoll
		add(10795, "Ape Atoll");

		// Tirannwn / Elven lands
		add(9265, "Lletya");
		add(8501, "Prifddinas");
		add(8757, "Prifddinas");

		// Fremennnik
		add(10807, "Rellekka");
		add(10551, "Rellekka");
		add(11068, "Miscellania");

		// Keldagrim
		add(11423, "Keldagrim");
		add(11422, "Keldagrim");

		// Lunar Isle
		add(8253, "Lunar Isle");

		// Kourend
		add(6971, "Shayzien");
		add(6970, "Shayzien");
		add(7226, "Hosidius");
		add(6966, "Kourend Castle / Lovakengj");
		add(6455, "Arceuus");
		add(6710, "Piscarilius");

		// Zeah general
		add(6459, "Xeric's Lookout");

		// God Wars Dungeon
		add(11578, "God Wars Dungeon");

		// Catacombs of Kourend
		add(6557, "Catacombs of Kourend");

		// Corsair Cove
		add(10028, "Corsair Cove");

		// Ancient Cavern
		add(6483, "Ancient Cavern");

		// Edgeville Dungeon / Asgarnian Ice Dungeon
		add(12441, "Edgeville Dungeon");
		add(12442, "Edgeville Dungeon");

		// Brimhaven Dungeon / Kalphite Lair
		add(10901, "Brimhaven Dungeon");
		add(13972, "Kalphite Lair");

		// Wilderness
		add(12343, "South Wilderness");
		add(12599, "Wilderness (Lava Dragon Isle)");
		add(13113, "Deep Wilderness");
		add(12090, "Wilderness (Revenant Caves area)");
	}

	private static void add(int regionId, String name)
	{
		// First-write wins so the static block order determines priority for shared IDs
		REGIONS.putIfAbsent(regionId, name);
	}

	private RegionNames()
	{
	}

	/**
	 * Returns a human-readable location string for the given world point, or a coordinate
	 * fallback if the region is not in the lookup table.
	 */
	static String nameFor(WorldPoint point)
	{
		if (point == null)
		{
			return null;
		}
		String named = REGIONS.get(point.getRegionID());
		return named != null ? named : String.format("Unknown area (x=%d, y=%d)", point.getX(), point.getY());
	}
}
