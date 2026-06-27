package com.runelive.sidekick.context.client;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps {@code VarPlayer.SLAYER_TASK_CREATURE} values to human-readable creature names.
 *
 * <p>IDs sourced from game data. Unknown IDs fall back to "Creature #N".
 */
final class SlayerTaskNames
{
	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		NAMES.put(1, "Banshees");
		NAMES.put(2, "Bats");
		NAMES.put(3, "Bears");
		NAMES.put(4, "Cave Bugs");
		NAMES.put(5, "Cave Crawlers");
		NAMES.put(6, "Cave Slimes");
		NAMES.put(7, "Cows");
		NAMES.put(8, "Crawling Hands");
		NAMES.put(9, "Desert Lizards");
		NAMES.put(10, "Dwarves");
		NAMES.put(11, "Experiments");
		NAMES.put(12, "Flesh Crawlers");
		NAMES.put(13, "Ghosts");
		NAMES.put(14, "Goblins");
		NAMES.put(15, "Hill Giants");
		NAMES.put(16, "Hobgoblins");
		NAMES.put(17, "Ice Warriors");
		NAMES.put(18, "Icefiends");
		NAMES.put(19, "Imps");
		NAMES.put(20, "Kalphites");
		NAMES.put(21, "Lizards");
		NAMES.put(22, "Minotaurs");
		NAMES.put(23, "Giant Moles");
		NAMES.put(24, "Monkeys");
		NAMES.put(25, "Moss Giants");
		NAMES.put(26, "Pirates");
		NAMES.put(27, "Rats");
		NAMES.put(28, "Rockslugs");
		NAMES.put(29, "Scorpions");
		NAMES.put(30, "Sea Snakes");
		NAMES.put(31, "Skeletons");
		NAMES.put(32, "Zombies");
		NAMES.put(33, "Wolves");
		NAMES.put(34, "Dagannoth");
		NAMES.put(35, "Bloodvelds");
		NAMES.put(36, "Jellies");
		NAMES.put(37, "Pyrefiends");
		NAMES.put(38, "Shades");
		NAMES.put(39, "Shadow Warriors");
		NAMES.put(40, "Spiritual Warriors");
		NAMES.put(41, "Gargoyles");
		NAMES.put(42, "Abyssal Demons");
		NAMES.put(43, "Aberrant Spectres");
		NAMES.put(44, "Basilisks");
		NAMES.put(45, "Black Demons");
		NAMES.put(46, "Black Dragons");
		NAMES.put(47, "Blue Dragons");
		NAMES.put(48, "Bronze Dragons");
		NAMES.put(49, "Chaos Druids");
		NAMES.put(50, "Dagannoth");
		NAMES.put(51, "Dark Beasts");
		NAMES.put(52, "Dust Devils");
		NAMES.put(53, "Earth Warriors");
		NAMES.put(54, "Elves");
		NAMES.put(55, "Fire Giants");
		NAMES.put(56, "Greater Demons");
		NAMES.put(57, "Hellhounds");
		NAMES.put(58, "Infernal Mages");
		NAMES.put(59, "Iron Dragons");
		NAMES.put(60, "Kurask");
		NAMES.put(61, "Lesser Demons");
		NAMES.put(62, "Nechryael");
		NAMES.put(63, "Red Dragons");
		NAMES.put(64, "Scabarites");
		NAMES.put(65, "Spiritual Mages");
		NAMES.put(66, "Steel Dragons");
		NAMES.put(67, "Suqahs");
		NAMES.put(68, "Terror Dogs");
		NAMES.put(69, "Trolls");
		NAMES.put(70, "TzHaar");
		NAMES.put(71, "Vampyres");
		NAMES.put(72, "Wyverns");
		NAMES.put(73, "Black Knights");
		NAMES.put(74, "Zygomites");
		NAMES.put(75, "Aviansies");
		NAMES.put(76, "Brine Rats");
		NAMES.put(77, "Cave Horrors");
		NAMES.put(78, "Cockatrices");
		NAMES.put(79, "Fever Spiders");
		NAMES.put(80, "Mutated Zygomites");
		NAMES.put(81, "Skeletal Wyverns");
		NAMES.put(82, "Smoke Devils");
		NAMES.put(83, "Turoth");
		NAMES.put(84, "Waterfiends");
		NAMES.put(85, "Wyrms");
		NAMES.put(86, "Drakes");
		NAMES.put(87, "Hydras");
		NAMES.put(88, "Grotesque Guardians");
		NAMES.put(89, "Adamant Dragons");
		NAMES.put(90, "Rune Dragons");
		NAMES.put(91, "Fossil Island Wyverns");
		NAMES.put(92, "Lizardmen");
		NAMES.put(93, "Lizardman Brutes");
		NAMES.put(94, "Cerberus");
		NAMES.put(95, "Abyssal Sire");
		NAMES.put(96, "Kraken");
		NAMES.put(97, "Thermonuclear Smoke Devil");
		NAMES.put(98, "Demonic Gorillas");
		NAMES.put(99, "Alchemical Hydra");
		NAMES.put(100, "Guardians of the Rift");
		NAMES.put(101, "Vampyres");
		NAMES.put(102, "Spitting Wyverns");
		NAMES.put(103, "Sulphur Lizards");
		NAMES.put(104, "Warped Jellies");
		NAMES.put(105, "Warped Terrorbirds");
		NAMES.put(106, "Ghouls");
		NAMES.put(107, "Ankou");
		NAMES.put(108, "Mutated Bloodvelds");
		NAMES.put(109, "Superior Slayer Creatures");
		NAMES.put(110, "Duke Sucellus");
		NAMES.put(111, "The Leviathan");
		NAMES.put(112, "The Whisperer");
		NAMES.put(113, "Vardorvis");
		NAMES.put(127, "Araxytes");
	}

	private SlayerTaskNames()
	{
	}

	/**
	 * Returns the creature name for the given creature ID, or {@code "Creature #N"} if unknown.
	 */
	static String nameFor(int creatureId)
	{
		return NAMES.getOrDefault(creatureId, "Creature #" + creatureId);
	}
}
