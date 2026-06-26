package com.runelive.sidekick.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * A token-bucket rate limiter used to keep us well under the community OSRS APIs' published
 * limits (e.g. WiseOldMan allows a modest number of requests per minute for anonymous clients).
 *
 * <p>The bucket holds up to {@code maxBurst} tokens and refills continuously at
 * {@code refillTokens} per {@code refillPeriod}. {@link #tryAcquire()} is non-blocking;
 * {@link #acquire()} parks (never {@link Thread#sleep}, per the plugin threading rules) until a
 * token is available. Time comes from an injected {@link Clock} so the permit logic is testable
 * without real waiting.
 *
 * <p>{@link #acquire()} is only ever called from background executor / OkHttp threads — never the
 * (future) RuneLite client thread.
 */
public final class RateLimiter
{
	private final double capacity;
	private final double refillPerMilli;
	private final Clock clock;

	private double tokens;
	private long lastRefillMillis;

	public RateLimiter(long maxBurst, long refillTokens, Duration refillPeriod, Clock clock)
	{
		if (maxBurst <= 0 || refillTokens <= 0 || refillPeriod.toMillis() <= 0)
		{
			throw new IllegalArgumentException("rate limiter parameters must be positive");
		}
		this.capacity = maxBurst;
		this.refillPerMilli = (double) refillTokens / refillPeriod.toMillis();
		this.clock = clock;
		this.tokens = maxBurst;
		this.lastRefillMillis = clock.millis();
	}

	/** Attempts to take one token without waiting. Returns {@code true} if one was available. */
	public synchronized boolean tryAcquire()
	{
		refill();
		if (tokens >= 1.0)
		{
			tokens -= 1.0;
			return true;
		}
		return false;
	}

	/** Milliseconds until the next token becomes available (0 if one is available now). */
	public synchronized long millisUntilNextPermit()
	{
		refill();
		if (tokens >= 1.0)
		{
			return 0;
		}
		return (long) Math.ceil((1.0 - tokens) / refillPerMilli);
	}

	/** Blocks (by parking) until a token is available, then takes it. */
	public void acquire()
	{
		while (true)
		{
			long waitMillis;
			synchronized (this)
			{
				refill();
				if (tokens >= 1.0)
				{
					tokens -= 1.0;
					return;
				}
				waitMillis = (long) Math.ceil((1.0 - tokens) / refillPerMilli);
			}
			if (waitMillis > 0)
			{
				LockSupport.parkNanos(Duration.ofMillis(waitMillis).toNanos());
			}
		}
	}

	private void refill()
	{
		long now = clock.millis();
		long elapsed = now - lastRefillMillis;
		if (elapsed > 0)
		{
			tokens = Math.min(capacity, tokens + elapsed * refillPerMilli);
			lastRefillMillis = now;
		}
	}
}
