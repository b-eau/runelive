package com.runelive.sidekick.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A {@link Clock} whose time advances only when the test tells it to — for deterministic TTL/rate tests. */
public final class MutableClock extends Clock
{
	private Instant now;
	private final ZoneId zone;

	public MutableClock(Instant start)
	{
		this(start, ZoneOffset.UTC);
	}

	private MutableClock(Instant start, ZoneId zone)
	{
		this.now = start;
		this.zone = zone;
	}

	public static MutableClock atEpoch()
	{
		return new MutableClock(Instant.ofEpochMilli(0));
	}

	public void advance(Duration duration)
	{
		now = now.plus(duration);
	}

	@Override
	public ZoneId getZone()
	{
		return zone;
	}

	@Override
	public Clock withZone(ZoneId newZone)
	{
		return new MutableClock(now, newZone);
	}

	@Override
	public Instant instant()
	{
		return now;
	}

	@Override
	public long millis()
	{
		return now.toEpochMilli();
	}
}
