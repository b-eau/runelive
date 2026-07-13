// Adds a proposed goal to the profile (one-click accept from the goal
// proposals). Authenticated and ownership-checked.

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";

export async function POST(req: NextRequest) {
  const { profileId, title, notes } = (await req.json().catch(() => ({}))) as {
    profileId?: string;
    title?: string;
    notes?: string;
  };
  if (!profileId || !title?.trim()) {
    return NextResponse.json({ error: "profileId and title required" }, { status: 400 });
  }
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  await db.goal.create({
    data: {
      profileId,
      title: title.trim().slice(0, 200),
      notes: notes?.trim() ? notes.trim().slice(0, 300) : null,
    },
  });
  return NextResponse.json({ ok: true });
}
