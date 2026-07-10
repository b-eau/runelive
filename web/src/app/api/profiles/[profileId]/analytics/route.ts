// Analytical time-series queries over the daily rollup tables.
//
// GET /api/profiles/:profileId/analytics
//   ?metric=skill_xp&skill=mining          — cumulative XP + per-day gains
//   ?metric=bank_value                     — bank value over time
//   ?metric=boss_kc&boss=zulrah            — kill count over time
//   &from=2023-01-01&to=2025-12-31         — optional range (UTC days)
//   &granularity=day|week|month            — bucket size, default day
//
// Responses: { series: [{ date, value, delta }] } where value is the last
// observation in the bucket and delta is the gain vs the previous bucket.

import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";

type Row = { date: Date; value: bigint | number };

function bucketKey(d: Date, granularity: string): string {
  if (granularity === "month") return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}-01`;
  if (granularity === "week") {
    const day = new Date(d);
    day.setUTCDate(day.getUTCDate() - ((day.getUTCDay() + 6) % 7)); // Monday
    return day.toISOString().slice(0, 10);
  }
  return d.toISOString().slice(0, 10);
}

export async function GET(req: NextRequest, ctx: { params: Promise<{ profileId: string }> }) {
  const { profileId } = await ctx.params;
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const q = req.nextUrl.searchParams;
  const metric = q.get("metric") ?? "skill_xp";
  const granularity = q.get("granularity") ?? "day";
  const from = q.get("from") ? new Date(q.get("from")!) : undefined;
  const to = q.get("to") ? new Date(q.get("to")!) : undefined;
  const dateFilter = { ...(from ? { gte: from } : {}), ...(to ? { lte: to } : {}) };

  let rows: Row[];
  if (metric === "skill_xp") {
    const skill = q.get("skill") ?? "overall";
    rows = (
      await db.xpSample.findMany({
        where: { profileId, skill, ...(from || to ? { date: dateFilter } : {}) },
        orderBy: { date: "asc" },
      })
    ).map((r) => ({ date: r.date, value: r.xp }));
  } else if (metric === "bank_value") {
    rows = (
      await db.bankValueSample.findMany({
        where: { profileId, ...(from || to ? { date: dateFilter } : {}) },
        orderBy: { date: "asc" },
      })
    ).map((r) => ({ date: r.date, value: r.value }));
  } else if (metric === "boss_kc") {
    const boss = q.get("boss") ?? "";
    rows = (
      await db.kcSample.findMany({
        where: { profileId, boss, ...(from || to ? { date: dateFilter } : {}) },
        orderBy: { date: "asc" },
      })
    ).map((r) => ({ date: r.date, value: r.kc }));
  } else {
    return NextResponse.json({ error: `Unknown metric ${metric}` }, { status: 400 });
  }

  // Bucket: last observation per bucket wins (values are cumulative).
  const buckets = new Map<string, number>();
  for (const r of rows) buckets.set(bucketKey(r.date, granularity), Number(r.value));

  const series: { date: string; value: number; delta: number }[] = [];
  let prev: number | null = null;
  for (const [date, value] of [...buckets.entries()].sort()) {
    series.push({ date, value, delta: prev === null ? 0 : value - prev });
    prev = value;
  }
  return NextResponse.json({ metric, granularity, series });
}
