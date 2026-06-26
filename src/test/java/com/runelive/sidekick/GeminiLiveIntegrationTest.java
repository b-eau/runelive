package com.runelive.sidekick;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.runelive.sidekick.llm.LlmProvider;
import com.runelive.sidekick.web.ChatDtos;
import java.util.List;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * A real end-to-end test against the live Gemini API and the live community OSRS APIs.
 *
 * <p>Skipped unless {@code GEMINI_API_KEY} is set, so it never runs (or costs anything) in a normal
 * {@code ./gradlew test}. When enabled it exercises the whole pipeline for real: fetch a player's
 * context from WiseOldMan, run the Gemini tool loop, price an item via the live GE API, and produce
 * a personalised answer. Set {@code SIDEKICK_MODEL} to pick the model (default a cheap flash-lite).
 */
public class GeminiLiveIntegrationTest
{
	private String apiKey;
	private Sidekick sidekick;

	@Before
	public void setUp()
	{
		apiKey = System.getenv("GEMINI_API_KEY");
		Assume.assumeTrue("set GEMINI_API_KEY to run the live Gemini integration test",
			apiKey != null && !apiKey.isEmpty());
	}

	@After
	public void tearDown()
	{
		if (sidekick != null)
		{
			sidekick.close();
		}
	}

	@Test
	public void personalisedAdviceWithLiveGeminiAndCommunityApis()
	{
		String model = System.getenv("SIDEKICK_MODEL");
		if (model == null || model.isEmpty())
		{
			model = "gemini-3.1-flash-lite";
		}

		sidekick = new Sidekick(SidekickConfig.builder()
			.provider(LlmProvider.GEMINI)
			.geminiApiKey(apiKey)
			.model(model)
			.maxTokens(2000)
			.build());

		ChatDtos.ChatRequest request = new ChatDtos.ChatRequest();
		request.player = "Zezima";
		request.modality = "text";
		request.messages = List.of(new ChatDtos.Turn("user",
			"How much does an Abyssal whip cost right now, and given my account is it a sensible buy?"));

		ChatDtos.ChatResponse response = sidekick.getChatService().handle(request);

		System.out.println("=== model: " + model + " ===");
		System.out.println("=== reply ===\n" + response.reply);
		System.out.println("=== tools used ===");
		if (response.tools != null)
		{
			for (ChatDtos.ToolCallDto tool : response.tools)
			{
				System.out.println("  " + tool.name + " " + tool.input + " -> " + tool.output);
			}
		}

		assertNull("no error: " + response.error, response.error);
		assertNotNull(response.reply);
		assertFalse(response.reply.trim().isEmpty());
		assertNotNull(response.context);
		assertEquals("Zezima", response.context.username);
	}
}
