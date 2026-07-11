// Links an account by username alone — no plugin required. Pulls the
// public hiscores snapshot and materializes it into a placeholder account
// that a later plugin link upgrades in place.

import { NextRequest, NextResponse } from "next/server";
import { currentUser } from "@/lib/auth";
import { PlayerNotFoundError } from "@/lib/lookup";
import { AccountConflictError, linkByUsername } from "@/lib/rsnLink";
import { rateLimit } from "@/lib/ratelimit";

export const maxDuration = 60; // hiscores lookup + up to 5 pages of WOM history

export async function POST(req: NextRequest) {
  const user = await currentUser();
  if (!user) return NextResponse.json({ error: "Sign in first" }, { status: 401 });

  if (!rateLimit(`rsn-link:${user.id}`, 6, 60_000)) {
    return NextResponse.json({ error: "Slow down a little — try again in a minute." }, { status: 429 });
  }

  const { username } = (await req.json().catch(() => ({}))) as { username?: string };
  if (!username) return NextResponse.json({ error: "Missing username" }, { status: 400 });

  try {
    const result = await linkByUsername(user.id, username);
    return NextResponse.json({ ok: true, ...result });
  } catch (e) {
    if (e instanceof PlayerNotFoundError) {
      return NextResponse.json(
        { error: "No hiscores entry found for that username — check the spelling." },
        { status: 404 },
      );
    }
    if (e instanceof AccountConflictError) {
      return NextResponse.json({ error: e.message }, { status: 409 });
    }
    console.error("username link failed", e);
    return NextResponse.json({ error: "Lookup hit a snag. Try again in a moment." }, { status: 500 });
  }
}
