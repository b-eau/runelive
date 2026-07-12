// Item catalog upsert: semantic item data behind search_bank / lookup_item.

import { beforeEach, describe, expect, it } from "vitest";
import { db } from "@/lib/db";
import { upsertCatalogRows } from "@/lib/itemCatalog";

describe("upsertCatalogRows", () => {
  beforeEach(async () => {
    await db.itemPrice.deleteMany();
  });

  it("inserts new items including untradeables", async () => {
    await upsertCatalogRows([
      { itemId: 9813, name: "Quest point cape", price: 0, examine: null, highAlch: null, members: true },
      { itemId: 4151, name: "Abyssal whip", price: 1_500_000, examine: "A weapon from the abyss.", highAlch: 72_000, members: true },
    ]);
    const cape = await db.itemPrice.findUnique({ where: { itemId: 9813 } });
    expect(cape?.name).toBe("Quest point cape");
    const whip = await db.itemPrice.findUnique({ where: { itemId: 4151 } });
    expect(whip?.examine).toBe("A weapon from the abyss.");
    expect(whip?.highAlch).toBe(72_000);
  });

  it("updates prices in place but keeps enrichment when re-sync lacks it", async () => {
    await upsertCatalogRows([
      { itemId: 4151, name: "Abyssal whip", price: 1_500_000, examine: "A weapon from the abyss.", highAlch: 72_000, members: true },
    ]);
    await upsertCatalogRows([
      { itemId: 4151, name: "Abyssal whip", price: 1_650_000, examine: null, highAlch: null, members: null },
    ]);
    const whip = await db.itemPrice.findUnique({ where: { itemId: 4151 } });
    expect(whip?.price).toBe(1_650_000);
    expect(whip?.examine).toBe("A weapon from the abyss."); // COALESCE keeps it
  });
});
