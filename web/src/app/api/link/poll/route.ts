// Polled by the RuneLite plugin. Once the user has claimed the code in the
// browser, this returns the freshly minted API token exactly once, then
// scrubs it.

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";

export async function GET(req: NextRequest) {
  const code = req.nextUrl.searchParams.get("code") ?? "";
  const pollSecret = req.nextUrl.searchParams.get("pollSecret") ?? "";
  const link = await db.linkCode.findUnique({ where: { code } });

  if (!link || link.pollSecret !== pollSecret) {
    return NextResponse.json({ status: "UNKNOWN" }, { status: 404 });
  }
  if (link.status === "PENDING" && link.expiresAt < new Date()) {
    return NextResponse.json({ status: "EXPIRED" });
  }
  if (link.status !== "CLAIMED") {
    return NextResponse.json({ status: "PENDING" });
  }
  if (!link.apiToken) {
    // Token already handed out on an earlier poll.
    return NextResponse.json({ status: "CONSUMED" });
  }
  const token = link.apiToken;
  await db.linkCode.update({ where: { code }, data: { apiToken: null } });
  return NextResponse.json({ status: "CLAIMED", apiToken: token });
}
