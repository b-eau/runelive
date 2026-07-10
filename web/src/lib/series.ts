// Server-side helpers that turn rollup rows into chart-ready series.

import { db } from "./db";
import type { TrendPoint } from "@/components/TrendChart";

function toPoints(rows: { date: Date; value: number }[]): TrendPoint[] {
  const sorted = rows.sort((a, b) => +a.date - +b.date);
  let prev: number | null = null;
  return sorted.map((r) => {
    const delta = prev === null ? 0 : r.value - prev;
    prev = r.value;
    return { date: r.date.toISOString().slice(0, 10), value: r.value, delta };
  });
}

export async function xpSeries(profileId: string, skill: string, days = 365): Promise<TrendPoint[]> {
  const since = new Date(Date.now() - days * 86400_000);
  const rows = await db.xpSample.findMany({
    where: { profileId, skill, date: { gte: since } },
    orderBy: { date: "asc" },
  });
  return toPoints(rows.map((r) => ({ date: r.date, value: Number(r.xp) })));
}

export async function bankSeries(profileId: string, days = 365): Promise<TrendPoint[]> {
  const since = new Date(Date.now() - days * 86400_000);
  const rows = await db.bankValueSample.findMany({
    where: { profileId, date: { gte: since } },
    orderBy: { date: "asc" },
  });
  return toPoints(rows.map((r) => ({ date: r.date, value: Number(r.value) })));
}

export async function kcSeries(profileId: string, boss: string, days = 730): Promise<TrendPoint[]> {
  const since = new Date(Date.now() - days * 86400_000);
  const rows = await db.kcSample.findMany({
    where: { profileId, boss, date: { gte: since } },
    orderBy: { date: "asc" },
  });
  return toPoints(rows.map((r) => ({ date: r.date, value: r.kc })));
}

/** XP gained per skill over the trailing N days, sorted descending. */
export async function recentGains(profileId: string, days = 7) {
  const since = new Date(Date.now() - days * 86400_000);
  const rows = await db.xpSample.findMany({
    where: { profileId, date: { gte: since }, skill: { not: "overall" } },
    orderBy: { date: "asc" },
  });
  const bySkill = new Map<string, { first: bigint; last: bigint }>();
  for (const r of rows) {
    const entry = bySkill.get(r.skill);
    if (!entry) bySkill.set(r.skill, { first: r.xp, last: r.xp });
    else entry.last = r.xp;
  }
  return [...bySkill.entries()]
    .map(([skill, { first, last }]) => ({ skill, gained: Number(last - first) }))
    .filter((g) => g.gained > 0)
    .sort((a, b) => b.gained - a.gained);
}
