package com.runelive.sidekick.conversation;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Persists {@link Conversation}s to disk as JSON, one file per conversation, under
 * {@code .runelite/osrs-sidekick/conversations/<username>/}. Threads are scoped per account so
 * switching characters never mixes histories.
 *
 * <p>All methods perform blocking disk I/O and must be called from a background thread (never the
 * client thread / EDT). I/O failures are logged and swallowed — conversation history is best-effort
 * and must never break a chat turn.
 */
@Slf4j
public class ConversationStore
{
	private final Gson gson;

	public ConversationStore(Gson gson)
	{
		this.gson = gson;
	}

	/** Writes (or overwrites) the conversation's JSON file. No-op for an empty thread. */
	public void save(Conversation conversation)
	{
		if (conversation == null || conversation.isEmpty()
			|| conversation.getId() == null || conversation.getUsername() == null)
		{
			return;
		}
		try
		{
			Path dir = userDir(conversation.getUsername());
			Files.createDirectories(dir);
			Path file = dir.resolve(conversation.getId() + ".json");
			Files.write(file, gson.toJson(conversation).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Failed to save conversation {}", conversation.getId(), e);
		}
	}

	/** Returns all of a player's conversations, most-recently-updated first. */
	public List<Conversation> list(String username)
	{
		List<Conversation> result = new ArrayList<>();
		Path dir = userDir(username);
		if (!Files.isDirectory(dir))
		{
			return result;
		}
		try (java.util.stream.Stream<Path> files = Files.list(dir))
		{
			files.filter(p -> p.toString().endsWith(".json")).forEach(p ->
			{
				Conversation c = read(p);
				if (c != null && !c.isEmpty())
				{
					result.add(c);
				}
			});
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Failed to list conversations for {}", username, e);
		}
		result.sort(Comparator.comparingLong(Conversation::getUpdatedAt).reversed());
		return result;
	}

	/** Loads a single conversation by id, or {@code null} if absent/unreadable. */
	public Conversation load(String username, String id)
	{
		if (id == null || id.isEmpty())
		{
			return null;
		}
		return read(userDir(username).resolve(id + ".json"));
	}

	private Conversation read(Path file)
	{
		try
		{
			if (!Files.isRegularFile(file))
			{
				return null;
			}
			String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			return gson.fromJson(json, Conversation.class);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Failed to read conversation file {}", file, e);
			return null;
		}
	}

	/** Resolved lazily so the store can be subclassed/instantiated without RuneLite on the path. */
	private static Path userDir(String username)
	{
		return new File(RuneLite.RUNELITE_DIR, "osrs-sidekick/conversations").toPath()
			.resolve(sanitize(username));
	}

	/** Keeps the directory name filesystem-safe; usernames are short and ASCII-ish in OSRS. */
	private static String sanitize(String username)
	{
		if (username == null || username.trim().isEmpty())
		{
			return "_unknown";
		}
		return username.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "_");
	}
}
