// On-demand goal recommendations for an account that already has goals. Unlike
// /api/goals/propose (the one-time onboarding nudge), this reads the player's
// current goals so it never repeats them, and honors ?refresh=1 to regenerate
// a fresh set instead of serving the cache.

import { NextRequest, NextResponse } from "next/server";
import { authorizedProfile } from "@/lib/data";
import { recommendGoals } from "@/lib/suggest";

export const maxDuration = 30;

export async function GET(req: NextRequest) {
  const profileId = req.nextUrl.searchParams.get("profileId");
  if (!profileId) return NextResponse.json({ error: "profileId required" }, { status: 400 });
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const refresh = req.nextUrl.searchParams.get("refresh") === "1";
  return NextResponse.json({ goals: await recommendGoals(profileId, { refresh }) });
}
