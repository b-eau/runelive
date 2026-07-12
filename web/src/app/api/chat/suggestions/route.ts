// Personalized conversation starters for the sidekick chat and each profile
// tab. Cached per (profile, context) in lib/suggest.ts, so cheap to call on
// every page/chat open.

import { NextRequest, NextResponse } from "next/server";
import { authorizedProfile } from "@/lib/data";
import { SUGGEST_CONTEXTS, suggestProfileQueries, type SuggestContext } from "@/lib/suggest";

export const maxDuration = 30;

export async function GET(req: NextRequest) {
  const profileId = req.nextUrl.searchParams.get("profileId");
  if (!profileId) return NextResponse.json({ error: "profileId required" }, { status: 400 });
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const raw = req.nextUrl.searchParams.get("context");
  const context: SuggestContext = SUGGEST_CONTEXTS.includes(raw as SuggestContext)
    ? (raw as SuggestContext)
    : "chat";

  return NextResponse.json({ suggestions: await suggestProfileQueries(profileId, context) });
}
