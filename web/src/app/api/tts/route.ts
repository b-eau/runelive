// Text-to-speech for voice mode. Streams ElevenLabs audio when
// ELEVENLABS_API_KEY is configured; the client falls back to the browser's
// speechSynthesis when this route is unavailable or errors.

import { NextRequest, NextResponse } from "next/server";
import { currentUser } from "@/lib/auth";
import { rateLimit } from "@/lib/ratelimit";

export const maxDuration = 30;

// "Lily" — ElevenLabs premade voice: calm, clear, British female. OSRS is a
// British game, so this reads as native to the audience. Override with
// ELEVENLABS_VOICE_ID (no deploy needed beyond the env change).
const DEFAULT_VOICE_ID = "pFZP5JQG7iQjIQuC4Bku";
const MAX_TEXT_LENGTH = 1200; // matches what the client would speak anyway

export async function POST(req: NextRequest) {
  const apiKey = process.env.ELEVENLABS_API_KEY;
  if (!apiKey) return NextResponse.json({ error: "TTS not configured" }, { status: 503 });

  const user = await currentUser();
  if (!user) return NextResponse.json({ error: "Sign in first" }, { status: 401 });
  if (!rateLimit(`tts:${user.id}`, 30, 60_000)) {
    return NextResponse.json({ error: "Slow down a little" }, { status: 429 });
  }

  const { text } = (await req.json().catch(() => ({}))) as { text?: string };
  if (!text?.trim()) return NextResponse.json({ error: "text required" }, { status: 400 });

  const voiceId = process.env.ELEVENLABS_VOICE_ID || DEFAULT_VOICE_ID;
  const res = await fetch(
    `https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voiceId)}?output_format=mp3_22050_32`,
    {
      method: "POST",
      headers: { "xi-api-key": apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({
        text: text.trim().slice(0, MAX_TEXT_LENGTH),
        // Flash: lowest latency tier — voice replies should feel instant.
        model_id: "eleven_flash_v2_5",
        // Stable + slightly quickened: quiet, unhurried delivery that
        // doesn't dawdle through longer answers.
        voice_settings: { stability: 0.55, similarity_boost: 0.75, speed: 1.07 },
      }),
      signal: AbortSignal.timeout(25_000),
    },
  );
  if (!res.ok || !res.body) {
    console.error(`elevenlabs tts failed: ${res.status} ${(await res.text().catch(() => "")).slice(0, 300)}`);
    return NextResponse.json({ error: "TTS failed" }, { status: 502 });
  }

  return new NextResponse(res.body, {
    headers: { "Content-Type": "audio/mpeg", "Cache-Control": "no-store" },
  });
}
