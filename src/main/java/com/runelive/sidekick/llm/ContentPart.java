package com.runelive.sidekick.llm;

/**
 * Provider-neutral piece of a message. Concrete parts are {@link TextPart}, {@link ToolUsePart} and
 * {@link ToolResultPart}. Each {@link LlmClient} translates these to/from its own wire format, so
 * the agent loop and the rest of the app never deal with a specific provider's JSON shape.
 */
public interface ContentPart
{
}
