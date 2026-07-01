package com.runelive.sidekick.goal;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Persists a player's {@link Goal}s to a single JSON file per account under
 * {@code .runelite/osrs-sidekick/goals/<username>.json}. All I/O is blocking and must run off the
 * client thread; failures are logged and swallowed so goal tracking never breaks a chat turn.
 */
@Slf4j
public class GoalStore
{
	/** Gson round-trips a list cleanly through a wrapper (no TypeToken needed). */
	private static final class GoalFile
	{
		List<Goal> goals = new ArrayList<>();
	}

	private final Gson gson;

	public GoalStore(Gson gson)
	{
		this.gson = gson;
	}

	public List<Goal> load(String username)
	{
		Path file = fileFor(username);
		try
		{
			if (!Files.isRegularFile(file))
			{
				return new ArrayList<>();
			}
			String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			GoalFile parsed = gson.fromJson(json, GoalFile.class);
			return parsed == null || parsed.goals == null ? new ArrayList<>() : parsed.goals;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Failed to load goals for {}", username, e);
			return new ArrayList<>();
		}
	}

	public void save(String username, List<Goal> goals)
	{
		try
		{
			Path file = fileFor(username);
			Files.createDirectories(file.getParent());
			GoalFile wrapper = new GoalFile();
			wrapper.goals = goals;
			Files.write(file, gson.toJson(wrapper).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Failed to save goals for {}", username, e);
		}
	}

	/** Resolved lazily so the store can be subclassed/instantiated without RuneLite on the path. */
	private static Path fileFor(String username)
	{
		return new File(RuneLite.RUNELITE_DIR, "osrs-sidekick/goals").toPath()
			.resolve(sanitize(username) + ".json");
	}

	private static String sanitize(String username)
	{
		if (username == null || username.trim().isEmpty())
		{
			return "_unknown";
		}
		return username.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
	}
}
