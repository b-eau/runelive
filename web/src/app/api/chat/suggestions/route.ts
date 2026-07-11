// Personalized conversation starters for the sidekick chat. Cached per
// profile in lib/suggest.ts, so this is cheap to call on every chat open.

import { NextRequest, NextResponse } from "next/server";
import { authorizedProfile } from "@/lib/data";
import { suggestProfileQueries } from "@/lib/suggest";

export const maxDuration = 30;

export async function GET(req: NextRequest) {
  const profileId = req.nextUrl.searchParams.get("profileId");
  if (!profileId) return NextResponse.json({ error: "profileId required" }, { status: 400 });
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  return NextResponse.json({ suggestions: await suggestProfileQueries(profileId) });
}
