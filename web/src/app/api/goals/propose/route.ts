// Proposes grounded account goals for a new user. Cached per profile (see
// proposeGoals), so the one-time generation cost isn't repaid on revisits.

import { NextRequest, NextResponse } from "next/server";
import { authorizedProfile } from "@/lib/data";
import { proposeGoals } from "@/lib/suggest";

export const maxDuration = 30;

export async function GET(req: NextRequest) {
  const profileId = req.nextUrl.searchParams.get("profileId");
  if (!profileId) return NextResponse.json({ error: "profileId required" }, { status: 400 });
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  return NextResponse.json({ goals: await proposeGoals(profileId) });
}
