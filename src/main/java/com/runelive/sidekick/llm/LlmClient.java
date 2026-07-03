package com.runelive.sidekick.llm;

/**
 * Abstraction over the chat model. Keeping this an interface lets the agent loop be exercised with
 * a scripted fake in tests, and lets the real Anthropic transport be swapped without touching the
 * agent. One {@link #complete} call is one round-trip (the agentic tool loop lives in the agent).
 */
public interface LlmClient
{
	LlmResult complete(LlmRequest request);
}
