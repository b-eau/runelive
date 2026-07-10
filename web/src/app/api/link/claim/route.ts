// Called from the /link page by the signed-in user to claim a plugin link
// code. Creates (or reuses) the OsrsAccount and mints an API token the
// plugin will pick up on its next poll.

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { currentUser, hashToken, newToken } from "@/lib/auth";

export async function POST(req: NextRequest) {
  const user = await currentUser();
  if (!user) return NextResponse.json({ error: "Sign in first" }, { status: 401 });

  const { code } = (await req.json().catch(() => ({}))) as { code?: string };
  if (!code) return NextResponse.json({ error: "Missing code" }, { status: 400 });

  const link = await db.linkCode.findUnique({ where: { code: code.toUpperCase().trim() } });
  if (!link || link.status !== "PENDING" || link.expiresAt < new Date()) {
    return NextResponse.json({ error: "That code is invalid or has expired. Generate a new one from the plugin." }, { status: 400 });
  }

  const existing = await db.osrsAccount.findUnique({ where: { accountHash: link.accountHash } });
  if (existing && existing.userId !== user.id) {
    return NextResponse.json(
      { error: "This game account is already linked to a different Sidekick user." },
      { status: 409 },
    );
  }

  const account =
    existing ??
    (await db.osrsAccount.create({
      data: { userId: user.id, accountHash: link.accountHash, displayName: link.displayName },
    }));
  if (existing && existing.displayName !== link.displayName) {
    await db.osrsAccount.update({ where: { id: existing.id }, data: { displayName: link.displayName } });
  }

  const apiToken = newToken();
  await db.apiToken.create({ data: { token: hashToken(apiToken), accountId: account.id } });
  await db.linkCode.update({
    where: { code: link.code },
    data: { status: "CLAIMED", apiToken, claimedAt: new Date() },
  });

  return NextResponse.json({ ok: true, displayName: link.displayName });
}
