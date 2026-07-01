package com.runelive.sidekick.goal;

/**
 * A long-term goal the player is working toward (e.g. "quest cape", "fire cape", "99 slayer").
 * Persisted per account by {@link GoalStore} and surfaced to the agent so advice stays aligned with
 * what the player actually wants.
 */
public class Goal
{
	private String id;
	private String text;
	private long createdAt;
	private boolean done;
	private long completedAt;

	public Goal()
	{
	}

	public Goal(String id, String text, long createdAt)
	{
		this.id = id;
		this.text = text;
		this.createdAt = createdAt;
	}

	public void markDone(long at)
	{
		this.done = true;
		this.completedAt = at;
	}

	public String getId()
	{
		return id;
	}

	public String getText()
	{
		return text == null ? "" : text;
	}

	public long getCreatedAt()
	{
		return createdAt;
	}

	public boolean isDone()
	{
		return done;
	}

	public long getCompletedAt()
	{
		return completedAt;
	}
}
