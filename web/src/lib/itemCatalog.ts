// Item catalog sync: gives the agent semantic knowledge of items instead of
// opaque ids. Three sources, merged into the ItemPrice table:
//
//   1. RuneLite's static cache dump — names for EVERY item, including
//      untradeables (quest capes, gear rewards, ...).
//   2. The OSRS wiki mapping — examine text, high alch, members flag for
//      tradeables.
//   3. The OSRS wiki latest prices — live GE guide prices.
//
// Runs from /api/cron/prices (daily Vercel cron + manual trigger).

import { Prisma } from "@prisma/client";
import { db } from "./db";

const USER_AGENT = "OSRS-Sidekick/1.0 (item catalog; https://github.com/b-eau/runelive)";
const NAMES_URL = "https://static.runelite.net/cache/item/names.json";
const MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
const LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";

type MappingEntry = {
  id: number;
  name: string;
  examine?: string;
  members?: boolean;
  highalch?: number;
};

async function fetchJson<T>(url: string): Promise<T> {
  const res = await fetch(url, {
    headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
    signal: AbortSignal.timeout(30_000),
  });
  if (!res.ok) throw new Error(`${url} -> ${res.status}`);
  return (await res.json()) as T;
}

export type CatalogRow = {
  itemId: number;
  name: string;
  price: number;
  examine: string | null;
  highAlch: number | null;
  members: boolean | null;
};

/** Chunked INSERT ... ON CONFLICT upsert so re-syncs update prices in place. */
export async function upsertCatalogRows(rows: CatalogRow[]): Promise<void> {
  const CHUNK = 1000;
  for (let i = 0; i < rows.length; i += CHUNK) {
    const chunk = rows.slice(i, i + CHUNK);
    const values = Prisma.join(
      chunk.map(
        (r) =>
          Prisma.sql`(${r.itemId}, ${r.name}, ${r.price}, ${r.examine}, ${r.highAlch}, ${r.members}, NOW())`,
      ),
    );
    await db.$executeRaw`
      INSERT INTO "ItemPrice" ("itemId", "name", "price", "examine", "highAlch", "members", "updatedAt")
      VALUES ${values}
      ON CONFLICT ("itemId") DO UPDATE SET
        "name" = EXCLUDED."name",
        "price" = EXCLUDED."price",
        "examine" = COALESCE(EXCLUDED."examine", "ItemPrice"."examine"),
        "highAlch" = COALESCE(EXCLUDED."highAlch", "ItemPrice"."highAlch"),
        "members" = COALESCE(EXCLUDED."members", "ItemPrice"."members"),
        "updatedAt" = NOW()`;
  }
}

export async function syncItemCatalog(): Promise<{ items: number; priced: number }> {
  const [names, mapping, latest] = await Promise.all([
    fetchJson<Record<string, string>>(NAMES_URL),
    fetchJson<MappingEntry[]>(MAPPING_URL),
    fetchJson<{ data: Record<string, { high: number | null; low: number | null }> }>(LATEST_URL),
  ]);

  const meta = new Map(mapping.map((m) => [m.id, m]));
  const rows: CatalogRow[] = [];
  let priced = 0;
  for (const [idStr, name] of Object.entries(names)) {
    const itemId = Number(idStr);
    if (!Number.isInteger(itemId) || !name || name === "null") continue;
    const m = meta.get(itemId);
    const quote = latest.data[idStr];
    const price = quote?.high ?? quote?.low ?? 0;
    if (price > 0) priced++;
    rows.push({
      itemId,
      name,
      price,
      examine: m?.examine ?? null,
      highAlch: m?.highalch ?? null,
      members: m?.members ?? null,
    });
  }

  await upsertCatalogRows(rows);
  return { items: rows.length, priced };
}
