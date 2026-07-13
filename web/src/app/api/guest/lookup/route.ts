// Guest player lookup: WOM with official-hiscores fallback, plus
// LLM-personalized starter queries. Unauthenticated but rate-limited.

import { NextRequest, NextResponse } from "next/server";
import { lookupPlayer, normalizeUsername, PlayerNotFoundError } from "@/lib/lookup";
import { proposeGuestGoals, suggestQueries } from "@/lib/guest";
import { clientIp, rateLimit } from "@/lib/ratelimit";

export const maxDuration = 30;

export async function POST(req: NextRequest) {
  if (!rateLimit(`guest-lookup:${clientIp(req)}`, 10, 60_000)) {
    return NextResponse.json({ error: "Slow down a little — try again in a minute." }, { status: 429 });
  }

  const { username } = (await req.json().catch(() => ({}))) as { username?: string };
  if (!username || !normalizeUsername(username)) {
    return NextResponse.json(
      { error: "Enter a valid RuneScape username (1–12 letters, numbers, or spaces)." },
      { status: 400 },
    );
  }

  try {
    const snapshot = await lookupPlayer(username);
    const [suggestions, goals] = await Promise.all([
      suggestQueries(snapshot),
      proposeGuestGoals(snapshot),
    ]);
    return NextResponse.json({ snapshot, suggestions, goals });
  } catch (e) {
    if (e instanceof PlayerNotFoundError) {
      return NextResponse.json(
        { error: `Couldn't find "${username}" on the hiscores. Check the spelling — brand-new or very low-level accounts may not be ranked yet.` },
        { status: 404 },
      );
    }
    console.error("guest lookup failed", e);
    return NextResponse.json(
      { error: "The hiscores aren't responding right now. Try again in a moment." },
      { status: 502 },
    );
  }
}
