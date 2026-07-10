// Called by the RuneLite plugin to begin account linking. Returns a short
// human-readable code plus a URL the plugin opens in the user's browser.
// The plugin then polls /api/link/poll with its pollSecret until the
// signed-in user claims the code and an API token is minted.

import { NextRequest, NextResponse } from "next/server";
import { randomInt } from "crypto";
import { db } from "@/lib/db";
import { appUrl, newToken } from "@/lib/auth";

const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O/1/I/L
const CODE_TTL_MS = 10 * 60 * 1000;

function humanCode(len = 8): string {
  let out = "";
  for (let i = 0; i < len; i++) out += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return out;
}

export async function POST(req: NextRequest) {
  const body = (await req.json().catch(() => ({}))) as {
    accountHash?: string | number;
    displayName?: string;
  };
  if (!body.accountHash || !body.displayName) {
    return NextResponse.json({ error: "accountHash and displayName are required" }, { status: 400 });
  }

  const code = humanCode();
  const pollSecret = newToken();
  await db.linkCode.create({
    data: {
      code,
      pollSecret,
      accountHash: String(body.accountHash),
      displayName: String(body.displayName).slice(0, 32),
      expiresAt: new Date(Date.now() + CODE_TTL_MS),
    },
  });

  return NextResponse.json({
    code,
    pollSecret,
    linkUrl: `${appUrl()}/link?code=${code}`,
    expiresInSeconds: CODE_TTL_MS / 1000,
  });
}
