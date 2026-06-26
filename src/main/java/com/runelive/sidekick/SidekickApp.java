package com.runelive.sidekick;

import lombok.extern.slf4j.Slf4j;

/**
 * Entry point for the standalone web harness. Reads configuration from the environment, wires the
 * sidekick, and serves the chat UI until interrupted.
 *
 * <p>Required: {@code ANTHROPIC_API_KEY}. Optional: {@code SIDEKICK_PLAYER} (default RSN),
 * {@code SIDEKICK_PORT} (default 8080), {@code SIDEKICK_MODEL} (default {@code claude-opus-4-8}).
 */
@Slf4j
public class SidekickApp
{
	public static void main(String[] args) throws Exception
	{
		SidekickConfig config = SidekickConfig.fromEnvironment();

		if (config.getAnthropicApiKey() == null)
		{
			log.warn("ANTHROPIC_API_KEY is not set — the UI will load but chat requests will fail until it is.");
		}

		Sidekick sidekick = new Sidekick(config);
		Runtime.getRuntime().addShutdownHook(new Thread(sidekick::close, "sidekick-shutdown"));
		sidekick.start();

		log.info("OSRS Sidekick is running. Open http://localhost:{} and enter your RSN to begin.",
			sidekick.getWebServer().boundPort());

		// Keep the process alive; the JDK HttpServer runs on its own (daemon) executor.
		Thread.currentThread().join();
	}
}
