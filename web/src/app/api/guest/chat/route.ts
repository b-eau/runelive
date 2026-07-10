// Limited guest chat: stateless (history comes from the client), no tools,
// cheap model, hard turn cap. The username is re-resolved server-side so the
// context can't be spoofed by the client.

import { NextRequest, NextResponse } from "next/server";
import { lookupPlayer, normalizeUsername, PlayerNotFoundError } from "@/lib/lookup";
import { GUEST_TURN_LIMIT, runGuestChat, type GuestMessage } from "@/lib/guest";
import { clientIp, rateLimit } from "@/lib/ratelimit";

export const maxDuration = 60;

export async function POST(req: NextRequest) {
  if (!rateLimit(`guest-chat:${clientIp(req)}`, 20, 60_000)) {
    return NextResponse.json({ error: "Slow down a little — try again in a minute." }, { status: 429 });
  }

  const body = (await req.json().catch(() => ({}))) as {
    username?: string;
    messages?: GuestMessage[];
  };
  if (!body.username || !normalizeUsername(body.username)) {
    return NextResponse.json({ error: "Missing username" }, { status: 400 });
  }
  const history = Array.isArray(body.messages) ? body.messages : [];
  if (history.length === 0 || history[history.length - 1]?.role !== "user") {
    return NextResponse.json({ error: "Last message must be from the user" }, { status: 400 });
  }

  const userTurns = history.filter((m) => m.role === "user").length;
  if (userTurns > GUEST_TURN_LIMIT) {
    return NextResponse.json({
      reply:
        "That's the guest limit for now! Sign up (it's free) to keep chatting — and link the RuneLite plugin so I can see your bank, quests, and progress over time, not just your hiscores.",
      limitReached: true,
    });
  }

  try {
    const snapshot = await lookupPlayer(body.username);
    const reply = await runGuestChat(snapshot, history);
    return NextResponse.json({ reply, limitReached: false, turnsRemaining: GUEST_TURN_LIMIT - userTurns });
  } catch (e) {
    if (e instanceof PlayerNotFoundError) {
      return NextResponse.json({ error: "Player not found" }, { status: 404 });
    }
    console.error("guest chat failed", e);
    return NextResponse.json({ error: "Sidekick hit a snag. Try again in a moment." }, { status: 500 });
  }
}
