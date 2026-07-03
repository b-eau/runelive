package com.runelive.sidekick.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A small thread-safe, time-to-live cache with single-flight loading.
 *
 * <p>Two properties matter for being a good citizen against the community OSRS APIs:
 * <ul>
 *   <li><b>TTL</b> — a value is reused until it expires, so repeated agent turns about the
 *       same player/item do not re-hit the upstream service.</li>
 *   <li><b>Single-flight</b> — concurrent requests for the same key share one in-flight load
 *       instead of issuing N identical upstream calls (a burst of chat messages coalesces).</li>
 * </ul>
 *
 * <p>Time is sourced from an injected {@link Clock} so expiry can be tested deterministically
 * without sleeping. Failed loads are not cached: if the loader throws, the entry is dropped so
 * the next caller retries.
 */
public final class TtlCache<K, V>
{
	private final Duration ttl;
	private final Clock clock;
	private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

	public TtlCache(Duration ttl, Clock clock)
	{
		this.ttl = ttl;
		this.clock = clock;
	}

	/**
	 * Returns the cached value for {@code key}, loading it via {@code loader} on a miss or after
	 * expiry. Concurrent callers for the same key block on a single shared load.
	 */
	public V get(K key, Supplier<V> loader)
	{
		// The boolean[] escapes the lambda to tell us whether *this* caller is the one that must
		// run the (potentially slow) loader. compute() runs under the bin lock, so it must stay
		// fast — we only create the promise here, never do IO inside it.
		final boolean[] mustLoad = {false};
		final Entry<V> entry = entries.compute(key, (k, current) ->
		{
			long now = clock.millis();
			if (current != null && now < current.expiresAtMillis && !current.future.isCompletedExceptionally())
			{
				return current;
			}
			mustLoad[0] = true;
			return new Entry<>(new CompletableFuture<>(), now + ttl.toMillis());
		});

		if (mustLoad[0])
		{
			try
			{
				entry.future.complete(loader.get());
			}
			catch (RuntimeException | Error e)
			{
				entry.future.completeExceptionally(e);
				// Don't cache failures: drop this entry so the next caller retries upstream.
				entries.remove(key, entry);
				throw e;
			}
		}
		return join(entry.future);
	}

	/** Drops a single key, forcing a reload on next access. */
	public void invalidate(K key)
	{
		entries.remove(key);
	}

	/** Drops every entry. */
	public void invalidateAll()
	{
		entries.clear();
	}

	private static <V> V join(CompletableFuture<V> future)
	{
		try
		{
			return future.join();
		}
		catch (CompletionException e)
		{
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException)
			{
				throw (RuntimeException) cause;
			}
			if (cause instanceof Error)
			{
				throw (Error) cause;
			}
			throw e;
		}
	}

	private static final class Entry<V>
	{
		final CompletableFuture<V> future;
		final long expiresAtMillis;

		Entry(CompletableFuture<V> future, long expiresAtMillis)
		{
			this.future = future;
			this.expiresAtMillis = expiresAtMillis;
		}
	}
}
