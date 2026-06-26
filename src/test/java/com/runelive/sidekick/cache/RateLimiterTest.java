package com.runelive.sidekick.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.runelive.sidekick.testutil.MutableClock;
import java.time.Duration;
import org.junit.Test;

public class RateLimiterTest
{
	@Test
	public void allowsBurstUpToCapacityThenBlocks()
	{
		MutableClock clock = MutableClock.atEpoch();
		// capacity 2, refill 1 token / 100ms
		RateLimiter limiter = new RateLimiter(2, 1, Duration.ofMillis(100), clock);

		assertTrue(limiter.tryAcquire());
		assertTrue(limiter.tryAcquire());
		assertFalse("burst exhausted", limiter.tryAcquire());
	}

	@Test
	public void refillsOverTime()
	{
		MutableClock clock = MutableClock.atEpoch();
		RateLimiter limiter = new RateLimiter(2, 1, Duration.ofMillis(100), clock);
		limiter.tryAcquire();
		limiter.tryAcquire();
		assertFalse(limiter.tryAcquire());

		clock.advance(Duration.ofMillis(100));
		assertTrue("one token refilled after the refill period", limiter.tryAcquire());
		assertFalse(limiter.tryAcquire());
	}

	@Test
	public void reportsTimeUntilNextPermit()
	{
		MutableClock clock = MutableClock.atEpoch();
		RateLimiter limiter = new RateLimiter(1, 1, Duration.ofMillis(100), clock);
		assertEquals(0, limiter.millisUntilNextPermit());
		limiter.tryAcquire();
		assertEquals(100, limiter.millisUntilNextPermit());

		clock.advance(Duration.ofMillis(40));
		assertEquals(60, limiter.millisUntilNextPermit());
	}

	@Test
	public void doesNotOverfillBeyondCapacity()
	{
		MutableClock clock = MutableClock.atEpoch();
		RateLimiter limiter = new RateLimiter(2, 1, Duration.ofMillis(100), clock);
		clock.advance(Duration.ofSeconds(10)); // lots of time passes
		assertTrue(limiter.tryAcquire());
		assertTrue(limiter.tryAcquire());
		assertFalse("bucket caps at capacity, not unlimited", limiter.tryAcquire());
	}
}
