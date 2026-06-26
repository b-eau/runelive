package com.runelive.sidekick.agent;

import lombok.Value;

/** A record of one tool call the agent made during a turn (surfaced to the UI for transparency). */
@Value
public class ToolInvocation
{
	String name;
	String input;
	String output;
	boolean error;
}
