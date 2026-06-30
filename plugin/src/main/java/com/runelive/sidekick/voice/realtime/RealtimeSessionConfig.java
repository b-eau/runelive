package com.runelive.sidekick.voice.realtime;

import com.runelive.sidekick.llm.ToolSpec;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Provider-neutral configuration for a realtime voice session: the system instructions (persona +
 * player context + memory), the tools the voice agent may call, the spoken voice, and the PCM audio
 * sample rates. Each {@link RealtimeVoiceSession} translates this to its provider's session shape.
 */
@Value
@Builder
public class RealtimeSessionConfig
{
	String model;
	String instructions;
	List<ToolSpec> tools;
	/** Spoken voice name (provider-specific); may be {@code null} for the provider default. */
	String voice;
	int inputSampleRateHz;
	int outputSampleRateHz;
}
