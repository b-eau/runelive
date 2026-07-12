// Daily item-catalog sync (names, semantics, live GE prices). Triggered by
// Vercel cron (see vercel.json) which sends Authorization: Bearer CRON_SECRET,
// or manually with the same header.

import { NextRequest, NextResponse } from "next/server";
import { syncItemCatalog } from "@/lib/itemCatalog";

export const maxDuration = 120;

export async function GET(req: NextRequest) {
  const secret = process.env.CRON_SECRET;
  if (!secret || req.headers.get("authorization") !== `Bearer ${secret}`) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }
  try {
    const result = await syncItemCatalog();
    console.log(`item catalog synced: ${result.items} items, ${result.priced} priced`);
    return NextResponse.json({ ok: true, ...result });
  } catch (e) {
    console.error("item catalog sync failed", e);
    return NextResponse.json({ error: "sync failed" }, { status: 500 });
  }
}
