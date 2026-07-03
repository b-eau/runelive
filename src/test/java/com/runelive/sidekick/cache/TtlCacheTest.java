package com.runelive.sidekick.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.runelive.sidekick.testutil.MutableClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class TtlCacheTest
{
	@Test
	public void servesFromCacheWithinTtl()
	{
		MutableClock clock = MutableClock.atEpoch();
		TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMinutes(5), clock);
		AtomicInteger loads = new AtomicInteger();

		assertEquals(Integer.valueOf(1), cache.get("k", loads::incrementAndGet));
		assertEquals(Integer.valueOf(1), cache.get("k", loads::incrementAndGet));
		assertEquals("loader runs only once within TTL", 1, loads.get());
	}

	@Test
	public void reloadsAfterExpiry()
	{
		MutableClock clock = MutableClock.atEpoch();
		TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMinutes(5), clock);
		AtomicInteger loads = new AtomicInteger();

		cache.get("k", loads::incrementAndGet);
		clock.advance(Duration.ofMinutes(6));
		cache.get("k", loads::incrementAndGet);
		assertEquals("loader runs again after TTL passes", 2, loads.get());
	}

	@Test
	public void doesNotCacheFailures()
	{
		MutableClock clock = MutableClock.atEpoch();
		TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMinutes(5), clock);
		AtomicInteger calls = new AtomicInteger();

		try
		{
			cache.get("k", () ->
			{
				calls.incrementAndGet();
				throw new IllegalStateException("boom");
			});
			fail("expected the loader exception to propagate");
		}
		catch (IllegalStateException expected)
		{
			// expected
		}

		// A failed load is not cached, so the next call retries and can succeed.
		assertEquals(Integer.valueOf(42), cache.get("k", () ->
		{
			calls.incrementAndGet();
			return 42;
		}));
		assertEquals(2, calls.get());
	}

	@Test
	public void invalidateForcesReload()
	{
		MutableClock clock = MutableClock.atEpoch();
		TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMinutes(5), clock);
		AtomicInteger loads = new AtomicInteger();

		cache.get("k", loads::incrementAndGet);
		cache.invalidate("k");
		cache.get("k", loads::incrementAndGet);
		assertEquals(2, loads.get());
	}

	@Test
	public void concurrentCallersShareOneLoad() throws Exception
	{
		MutableClock clock = MutableClock.atEpoch();
		TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMinutes(5), clock);
		AtomicInteger loads = new AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch firstInside = new CountDownLatch(1);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try
		{
			pool.submit(() -> cache.get("k", () ->
			{
				loads.incrementAndGet();
				firstInside.countDown();
				await(release);
				return 7;
			}));

			// Wait until the first caller is inside the loader, then fire a second caller for the same key.
			assertTrue(firstInside.await(2, TimeUnit.SECONDS));
			java.util.concurrent.Future<Integer> second =
				pool.submit(() -> cache.get("k", () ->
				{
					loads.incrementAndGet();
					return 99;
				}));

			// Give the second caller a moment to attach to the in-flight load, then release it.
			Thread.sleep(50);
			release.countDown();

			assertEquals(Integer.valueOf(7), second.get(2, TimeUnit.SECONDS));
			assertEquals("only one upstream load despite two concurrent callers", 1, loads.get());
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch)
	{
		try
		{
			latch.await(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
