// COMBAT_ACHIEVEMENTS / COLLECTION_LOG materialization and their context lines.

import { describe, expect, it } from "vitest";
import { db } from "@/lib/db";
import { materializeEvent } from "@/lib/materialize";
import { buildContext } from "@/lib/sidekick";
import { createTestProfile } from "./fixtures";

const THRESHOLDS = { EASY: 33, MEDIUM: 115, HARD: 304, ELITE: 820, MASTER: 1465, GRANDMASTER: 2525 };

describe("COMBAT_ACHIEVEMENTS materialization", () => {
  it("computes tier completion from points vs thresholds", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "COMBAT_ACHIEVEMENTS",
      occurredAt: "2026-07-12T12:00:00Z",
      payload: { points: 512, thresholds: THRESHOLDS },
    });

    const ca = await db.combatAchievementState.findUnique({ where: { profileId } });
    expect(ca?.points).toBe(512);
    expect(ca?.easy).toBe(true);
    expect(ca?.medium).toBe(true);
    expect(ca?.hard).toBe(true);
    expect(ca?.elite).toBe(false);
    expect(ca?.grandmaster).toBe(false);
  });

  it("never completes a tier whose threshold is missing/zero", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "COMBAT_ACHIEVEMENTS",
      occurredAt: "2026-07-12T12:00:00Z",
      payload: { points: 9999, thresholds: { EASY: 33 } },
    });
    const ca = await db.combatAchievementState.findUnique({ where: { profileId } });
    expect(ca?.easy).toBe(true);
    expect(ca?.grandmaster).toBe(false);
  });

  it("upserts on re-sync", async () => {
    const profileId = await createTestProfile();
    for (const points of [100, 900]) {
      await materializeEvent(profileId, {
        type: "COMBAT_ACHIEVEMENTS",
        occurredAt: "2026-07-12T12:00:00Z",
        payload: { points, thresholds: THRESHOLDS },
      });
    }
    const ca = await db.combatAchievementState.findUnique({ where: { profileId } });
    expect(ca?.points).toBe(900);
    expect(ca?.elite).toBe(true);
  });
});

describe("COLLECTION_LOG materialization", () => {
  it("stores overall and per-section counts", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "COLLECTION_LOG",
      occurredAt: "2026-07-12T12:00:00Z",
      payload: {
        obtained: 412,
        total: 1568,
        sections: { bosses: { obtained: 120, total: 500 }, raids: { obtained: 12, total: 87 } },
      },
    });
    const clog = await db.collectionLogState.findUnique({ where: { profileId } });
    expect(clog?.obtained).toBe(412);
    expect(clog?.total).toBe(1568);
    expect(JSON.parse(clog!.sections).bosses.obtained).toBe(120);
  });

  it("ignores unsynced (zero-total) payloads", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "COLLECTION_LOG",
      occurredAt: "2026-07-12T12:00:00Z",
      payload: { obtained: 0, total: 0 },
    });
    expect(await db.collectionLogState.findUnique({ where: { profileId } })).toBeNull();
  });
});

describe("COLLECTION_LOG_ITEMS materialization + search tool", () => {
  it("stores the slot universe with obtained flags and replaces on re-sync", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "COLLECTION_LOG_ITEMS",
      occurredAt: "2026-07-12T12:00:00Z",
      payload: { obtained: [20997], universe: [20997, 13652, 11785] },
    });

    expect(await db.collectionLogSlot.count({ where: { profileId } })).toBe(3);
    const tbow = await db.collectionLogSlot.findUnique({
      where: { profileId_itemId: { profileId, itemId: 20997 } },
    });
    expect(tbow?.obtained).toBe(true);
    const claws = await db.collectionLogSlot.findUnique({
      where: { profileId_itemId: { profileId, itemId: 13652 } },
    });
    expect(claws?.obtained).toBe(false);

    // Re-sync with more obtained replaces the old rows.
    await materializeEvent(profileId, {
      type: "COLLECTION_LOG_ITEMS",
      occurredAt: "2026-07-12T13:00:00Z",
      payload: { obtained: [20997, 13652], universe: [20997, 13652, 11785] },
    });
    const clawsAfter = await db.collectionLogSlot.findUnique({
      where: { profileId_itemId: { profileId, itemId: 13652 } },
    });
    expect(clawsAfter?.obtained).toBe(true);
  });

  it("search_collection_log resolves names via the item catalog", async () => {
    const profileId = await createTestProfile();
    await db.itemPrice.createMany({
      data: [
        { itemId: 20997, name: "Twisted bow", price: 1_490_000_000 },
        { itemId: 13652, name: "Dragon claws", price: 80_000_000 },
      ],
      skipDuplicates: true,
    });
    await materializeEvent(profileId, {
      type: "COLLECTION_LOG_ITEMS",
      occurredAt: "2026-07-12T12:00:00Z",
      payload: { obtained: [20997], universe: [20997, 13652] },
    });

    const { buildTools } = await import("@/lib/sidekick");
    const tool = buildTools(profileId).find((t) => t.name === "search_collection_log")!;
    const bow = String(await tool.run({ query: "twisted bow" } as never));
    expect(bow).toContain("Twisted bow — OBTAINED");
    const claws = String(await tool.run({ query: "dragon claws" } as never));
    expect(claws).toContain("Dragon claws — not obtained");
  });

  it("search_collection_log explains when nothing is synced", async () => {
    const profileId = await createTestProfile();
    const { buildTools } = await import("@/lib/sidekick");
    const tool = buildTools(profileId).find((t) => t.name === "search_collection_log")!;
    const result = String(await tool.run({ query: "pet" } as never));
    expect(result).toContain("No item-level collection log data synced yet");
  });
});

describe("buildContext achievement lines", () => {
  it("summarizes CA tiers and collection log counts", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "COMBAT_ACHIEVEMENTS",
      occurredAt: "2026-07-12T12:00:00Z",
      payload: { points: 512, thresholds: THRESHOLDS },
    });
    await materializeEvent(profileId, {
      type: "COLLECTION_LOG",
      occurredAt: "2026-07-12T12:00:00Z",
      payload: { obtained: 412, total: 1568, sections: {} },
    });

    const context = await buildContext(profileId);
    expect(context).toContain("Combat Achievements: 512 points; tiers complete: Easy, Medium, Hard.");
    expect(context).toContain("Collection log: 412/1568 uniques obtained.");
  });
});
