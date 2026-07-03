package com.runelive.sidekick.llm;

import lombok.Value;

/** Plain text content. */
@Value
public class TextPart implements ContentPart
{
	String text;
}
