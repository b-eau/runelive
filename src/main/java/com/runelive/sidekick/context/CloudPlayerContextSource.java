package com.runelive.sidekick.context;

import com.runelive.sidekick.cache.TtlCache;
import com.runelive.sidekick.context.wiseoldman.WiseOldManClient;
import java.util.Locale;

/**
 * {@link PlayerContextSource} backed by community hiscore APIs (currently WiseOldMan).
 *
 * <p>Results are cached per username with a short TTL so the many model turns of a single
 * conversation reuse one upstream fetch. Usernames are normalised (trimmed, lower-cased) so
 * "Zezima", "zezima " and "ZEZIMA" share a cache entry and never trigger duplicate calls.
 */
public class CloudPlayerContextSource implements PlayerContextSource
{
	private final WiseOldManClient wiseOldMan;
	private final TtlCache<String, PlayerContext> cache;

	public CloudPlayerContextSource(WiseOldManClient wiseOldMan, TtlCache<String, PlayerContext> cache)
	{
		this.wiseOldMan = wiseOldMan;
		this.cache = cache;
	}

	@Override
	public PlayerContext fetch(String username)
	{
		String normalised = normalise(username);
		if (normalised.isEmpty())
		{
			throw new IllegalArgumentException("username must not be blank");
		}
		// The cache key is normalised; the upstream call uses the same normalised form so display
		// names come back consistently.
		return cache.get(normalised, () -> wiseOldMan.fetchPlayer(normalised));
	}

	private static String normalise(String username)
	{
		return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
	}
}
