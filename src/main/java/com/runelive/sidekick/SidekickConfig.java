package com.runelive.sidekick;

import com.runelive.sidekick.llm.LlmProvider;
import lombok.Builder;
import lombok.Value;
import okhttp3.HttpUrl;

/**
 * User-facing configuration. In the harness these come from environment variables / system
 * properties; in the future RuneLite plugin they map onto {@code @ConfigItem} entries (config group
 * {@code "osrs-sidekick"}). Note: the feature submits the player's RSN to third-party community APIs,
 * so the plugin version of these toggles must be opt-in and carry the standard IP-disclosure warning.
 */
@Value
@Builder(toBuilder = true)
public class SidekickConfig
{
	@Builder.Default
	LlmProvider provider = LlmProvider.ANTHROPIC;

	String anthropicApiKey;
	@Builder.Default
	HttpUrl anthropicBaseUrl = HttpUrl.get("https://api.anthropic.com");

	String geminiApiKey;
	@Builder.Default
	HttpUrl geminiBaseUrl = HttpUrl.get("https://generativelanguage.googleapis.com");

	@Builder.Default
	String model = "claude-opus-4-8";
	@Builder.Default
	long maxTokens = 8000;
	@Builder.Default
	boolean thinking = true;

	@Builder.Default
	HttpUrl wiseOldManBaseUrl = HttpUrl.get("https://api.wiseoldman.net/v2");
	@Builder.Default
	HttpUrl pricesBaseUrl = HttpUrl.get("https://prices.runescape.wiki/api/v1/osrs");
	@Builder.Default
	HttpUrl wikiBaseUrl = HttpUrl.get("https://oldschool.runescape.wiki");

	/** Default RSN used when the UI doesn't specify one (nullable). */
	String defaultPlayer;
	@Builder.Default
	int port = 8080;
	@Builder.Default
	String userAgent = "osrs-sidekick/0.1 (RuneLite sidekick harness; +https://github.com/b-eau/runelive)";
	@Builder.Default
	int maxAgentSteps = 8;

	public static SidekickConfig fromEnvironment()
	{
		LlmProvider provider = LlmProvider.fromString(env("SIDEKICK_PROVIDER", "anthropic"));
		String defaultModel = provider == LlmProvider.GEMINI ? "gemini-3.5-flash" : "claude-opus-4-8";

		SidekickConfigBuilder builder = SidekickConfig.builder()
			.provider(provider)
			.anthropicApiKey(env("ANTHROPIC_API_KEY", null))
			.geminiApiKey(env("GEMINI_API_KEY", env("GOOGLE_API_KEY", null)))
			.model(env("SIDEKICK_MODEL", defaultModel))
			.defaultPlayer(env("SIDEKICK_PLAYER", null))
			.port(parseInt(env("SIDEKICK_PORT", "8080"), 8080))
			.thinking(!"false".equalsIgnoreCase(env("SIDEKICK_THINKING", "true")));

		String anthropicBase = env("SIDEKICK_ANTHROPIC_BASE_URL", null);
		if (anthropicBase != null)
		{
			builder.anthropicBaseUrl(HttpUrl.get(anthropicBase));
		}
		String geminiBase = env("SIDEKICK_GEMINI_BASE_URL", null);
		if (geminiBase != null)
		{
			builder.geminiBaseUrl(HttpUrl.get(geminiBase));
		}
		String womBase = env("SIDEKICK_WOM_BASE_URL", null);
		if (womBase != null)
		{
			builder.wiseOldManBaseUrl(HttpUrl.get(womBase));
		}
		String pricesBase = env("SIDEKICK_PRICES_BASE_URL", null);
		if (pricesBase != null)
		{
			builder.pricesBaseUrl(HttpUrl.get(pricesBase));
		}
		String wikiBase = env("SIDEKICK_WIKI_BASE_URL", null);
		if (wikiBase != null)
		{
			builder.wikiBaseUrl(HttpUrl.get(wikiBase));
		}
		return builder.build();
	}

	private static String env(String name, String fallback)
	{
		String value = System.getenv(name);
		if (value == null || value.isEmpty())
		{
			value = System.getProperty(name);
		}
		return value == null || value.isEmpty() ? fallback : value;
	}

	private static int parseInt(String value, int fallback)
	{
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}
}
