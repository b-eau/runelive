package com.runelive.sidekick.context;

/**
 * Supplies a {@link PlayerContext} for a given player.
 *
 * <p>This is the seam that makes the agent portable. Today the only implementation is
 * {@link CloudPlayerContextSource}, backed by community hiscore APIs. In the future RuneLite
 * plugin, a {@code ClientPlayerContextSource} backed by the live game client would implement the
 * same interface and feed the agent far richer data (bank, location, quests, diaries) — every
 * consumer of {@code PlayerContext} stays unchanged.
 */
public interface PlayerContextSource
{
	/**
	 * Fetches the current context for {@code username}.
	 *
	 * @throws PlayerNotFoundException if the player is unknown to the source
	 * @throws com.runelive.sidekick.http.HttpException on upstream failure
	 */
	PlayerContext fetch(String username);
}
