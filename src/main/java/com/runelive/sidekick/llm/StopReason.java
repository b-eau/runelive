package com.runelive.sidekick.llm;

/** Normalised view of the Anthropic Messages API {@code stop_reason}. */
public enum StopReason
{
	END_TURN,
	TOOL_USE,
	MAX_TOKENS,
	REFUSAL,
	PAUSE_TURN,
	OTHER;

	public static StopReason fromWire(String wire)
	{
		if (wire == null)
		{
			return OTHER;
		}
		switch (wire)
		{
			case "end_turn":
				return END_TURN;
			case "tool_use":
				return TOOL_USE;
			case "max_tokens":
				return MAX_TOKENS;
			case "refusal":
				return REFUSAL;
			case "pause_turn":
				return PAUSE_TURN;
			default:
				return OTHER;
		}
	}
}
