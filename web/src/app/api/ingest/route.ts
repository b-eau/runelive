// Event ingestion endpoint for the RuneLite plugin.
//
// POST /api/ingest
// Authorization: Bearer <apiToken>
// {
//   "profileKind": "STANDARD",          // STANDARD | LEAGUES | DEADMAN | ...
//   "accountType": "IRONMAN",           // optional
//   "displayName": "dummymitch",        // optional, refreshes the stored name
//   "events": [
//     { "type": "SKILLS", "occurredAt": "...", "dedupeKey": "...", "payload": {...} },
//     ...
//   ]
// }
//
// Events are appended to the immutable log and materialized into
// current-state + rollup tables. dedupeKey makes retries idempotent.

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { hashToken } from "@/lib/auth";
import { ingestEvents, type IngestEvent } from "@/lib/materialize";
import { PROFILE_KINDS } from "@/lib/osrs";

const MAX_EVENTS_PER_BATCH = 200;
const MAX_BODY_BYTES = 2 * 1024 * 1024;

export async function POST(req: NextRequest) {
  const auth = req.headers.get("authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : null;
  if (!token) return NextResponse.json({ error: "Missing bearer token" }, { status: 401 });

  const apiToken = await db.apiToken.findUnique({
    where: { token: hashToken(token) },
    include: { account: true },
  });
  if (!apiToken || apiToken.revokedAt) {
    return NextResponse.json({ error: "Invalid or revoked token" }, { status: 401 });
  }

  const raw = await req.text();
  if (raw.length > MAX_BODY_BYTES) {
    return NextResponse.json({ error: "Batch too large" }, { status: 413 });
  }
  let body: {
    profileKind?: string;
    accountType?: string;
    displayName?: string;
    events?: IngestEvent[];
  };
  try {
    body = JSON.parse(raw);
  } catch {
    return NextResponse.json({ error: "Invalid JSON" }, { status: 400 });
  }

  const kind = (body.profileKind ?? "STANDARD").toUpperCase();
  if (!(PROFILE_KINDS as readonly string[]).includes(kind)) {
    return NextResponse.json({ error: `Unknown profileKind ${kind}` }, { status: 400 });
  }
  const events = Array.isArray(body.events) ? body.events : [];
  if (events.length === 0) return NextResponse.json({ ingested: 0 });
  if (events.length > MAX_EVENTS_PER_BATCH) {
    return NextResponse.json({ error: `Max ${MAX_EVENTS_PER_BATCH} events per batch` }, { status: 400 });
  }
  for (const e of events) {
    if (!e.type || !e.occurredAt || Number.isNaN(+new Date(e.occurredAt))) {
      return NextResponse.json({ error: "Each event needs a type and a valid occurredAt" }, { status: 400 });
    }
  }

  const profile = await db.profile.upsert({
    where: { accountId_kind: { accountId: apiToken.accountId, kind } },
    update: body.accountType ? { accountType: body.accountType.toUpperCase() } : {},
    create: {
      accountId: apiToken.accountId,
      kind,
      accountType: (body.accountType ?? "REGULAR").toUpperCase(),
    },
  });

  if (body.displayName && body.displayName !== apiToken.account.displayName) {
    await db.osrsAccount.update({
      where: { id: apiToken.accountId },
      data: { displayName: String(body.displayName).slice(0, 32) },
    });
  }
  await db.apiToken.update({
    where: { token: apiToken.token },
    data: { lastUsedAt: new Date() },
  });

  const ingested = await ingestEvents(profile.id, events);
  return NextResponse.json({ ingested, profileId: profile.id });
}
