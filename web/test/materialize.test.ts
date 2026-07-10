import { beforeEach, describe, expect, it } from "vitest";
import { db } from "@/lib/db";
import { dayOf, ingestEvents, materializeEvent, priceItems } from "@/lib/materialize";
import { createTestProfile } from "./fixtures";

describe("dayOf", () => {
  it("floors a timestamp to UTC midnight", () => {
    expect(dayOf(new Date("2025-06-15T23:59:59Z")).toISOString()).toBe("2025-06-15T00:00:00.000Z");
    expect(dayOf(new Date("2025-06-15T00:00:00Z")).toISOString()).toBe("2025-06-15T00:00:00.000Z");
  });
});

describe("priceItems", () => {
  beforeEach(async () => {
    await db.itemPrice.deleteMany();
  });

  it("values coins at face value regardless of price table", async () => {
    expect(await priceItems([{ id: 995, qty: 1000 }])).toBe(1000n);
  });

  it("looks up prices from ItemPrice and multiplies by quantity", async () => {
    await db.itemPrice.create({ data: { itemId: 4151, name: "Abyssal whip", price: 2_000_000 } });
    const value = await priceItems([{ id: 4151, qty: 2 }]);
    expect(value).toBe(4_000_000n);
  });

  it("values unknown items at 0", async () => {
    const value = await priceItems([{ id: 999999, qty: 5 }]);
    expect(value).toBe(0n);
  });

  it("returns 0 for an empty item list without querying", async () => {
    expect(await priceItems([])).toBe(0n);
  });
});

describe("materializeEvent — SKILLS", () => {
  it("upserts SkillState and an XpSample row per skill", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "SKILLS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { skills: { attack: { xp: 100000, level: 42 }, mining: { xp: 50000, level: 35 } } },
    });

    const attack = await db.skillState.findUnique({ where: { profileId_skill: { profileId, skill: "attack" } } });
    expect(attack?.xp).toBe(100000n);
    expect(attack?.level).toBe(42);

    const sample = await db.xpSample.findUnique({
      where: { profileId_skill_date: { profileId, skill: "attack", date: dayOf(new Date("2025-06-15T12:00:00Z")) } },
    });
    expect(sample?.xp).toBe(100000n);
  });

  it("derives level from xp when level is omitted", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "SKILLS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { skills: { attack: { xp: 13034431 } } },
    });
    const attack = await db.skillState.findUnique({ where: { profileId_skill: { profileId, skill: "attack" } } });
    expect(attack?.level).toBe(99);
  });

  it("synthesizes an overall row from the sum of other skills when absent", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "SKILLS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { skills: { attack: { xp: 100, level: 1 }, mining: { xp: 200, level: 2 } } },
    });
    const overall = await db.skillState.findUnique({ where: { profileId_skill: { profileId, skill: "overall" } } });
    expect(overall?.xp).toBe(300n);
    expect(overall?.level).toBe(3);
  });

  it("does not overwrite a client-supplied overall row", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "SKILLS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: {
        skills: {
          attack: { xp: 100, level: 1 },
          overall: { xp: 999999, level: 500 },
        },
      },
    });
    const overall = await db.skillState.findUnique({ where: { profileId_skill: { profileId, skill: "overall" } } });
    expect(overall?.xp).toBe(999999n);
    expect(overall?.level).toBe(500);
  });

  it("clamps negative xp to 0", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "SKILLS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { skills: { attack: { xp: -50, level: 1 } } },
    });
    const attack = await db.skillState.findUnique({ where: { profileId_skill: { profileId, skill: "attack" } } });
    expect(attack?.xp).toBe(0n);
  });

  it("sets the profile combat level once at least 7 combat skills are present", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "SKILLS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: {
        skills: {
          attack: { xp: 13034431, level: 99 },
          strength: { xp: 13034431, level: 99 },
          defence: { xp: 13034431, level: 99 },
          hitpoints: { xp: 13034431, level: 99 },
          prayer: { xp: 13034431, level: 99 },
          ranged: { xp: 13034431, level: 99 },
          magic: { xp: 13034431, level: 99 },
        },
      },
    });
    const profile = await db.profile.findUnique({ where: { id: profileId } });
    expect(profile?.combatLevel).toBe(126);
  });

  it("upserting the same skill twice updates rather than duplicates", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "SKILLS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { skills: { attack: { xp: 100, level: 1 } } },
    });
    await materializeEvent(profileId, {
      type: "SKILLS",
      occurredAt: "2025-06-15T18:00:00Z",
      payload: { skills: { attack: { xp: 5000, level: 10 } } },
    });
    const rows = await db.skillState.findMany({ where: { profileId, skill: "attack" } });
    expect(rows).toHaveLength(1);
    expect(rows[0].xp).toBe(5000n);
  });
});

describe("materializeEvent — QUESTS", () => {
  it("upserts quest state", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "QUESTS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { quests: [{ name: "Cook's Assistant", state: "FINISHED" }, { name: "Dragon Slayer I", state: "IN_PROGRESS" }] },
    });
    const quests = await db.questState.findMany({ where: { profileId }, orderBy: { quest: "asc" } });
    expect(quests.map((q) => [q.quest, q.state])).toEqual([
      ["Cook's Assistant", "FINISHED"],
      ["Dragon Slayer I", "IN_PROGRESS"],
    ]);
  });

  it("re-syncing updates existing quest state in place", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "QUESTS",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { quests: [{ name: "Cook's Assistant", state: "NOT_STARTED" }] },
    });
    await materializeEvent(profileId, {
      type: "QUESTS",
      occurredAt: "2025-06-16T12:00:00Z",
      payload: { quests: [{ name: "Cook's Assistant", state: "FINISHED" }] },
    });
    const quests = await db.questState.findMany({ where: { profileId } });
    expect(quests).toHaveLength(1);
    expect(quests[0].state).toBe("FINISHED");
  });
});

describe("materializeEvent — DIARIES", () => {
  it("upserts diary completion per area/tier", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "DIARIES",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { diaries: [{ area: "Varrock", tier: "EASY", completed: true }] },
    });
    const diary = await db.diaryState.findUnique({
      where: { profileId_area_tier: { profileId, area: "Varrock", tier: "EASY" } },
    });
    expect(diary?.completed).toBe(true);
  });
});

describe("materializeEvent — containers", () => {
  beforeEach(async () => {
    await db.itemPrice.deleteMany();
    await db.itemPrice.create({ data: { itemId: 4151, name: "Abyssal whip", price: 1_500_000 } });
  });

  it("prices and stores the bank, and records a daily bank value sample", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "BANK",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { items: [{ id: 995, qty: 100 }, { id: 4151, qty: 1 }] },
    });
    const bank = await db.containerState.findUnique({
      where: { profileId_container: { profileId, container: "BANK" } },
    });
    expect(bank?.value).toBe(1_500_100n);

    const sample = await db.bankValueSample.findUnique({
      where: { profileId_date: { profileId, date: dayOf(new Date("2025-06-15T12:00:00Z")) } },
    });
    expect(sample?.value).toBe(1_500_100n);
  });

  it("stores inventory and equipment without a value-sample row", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "INVENTORY",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { items: [{ id: 4151, qty: 1 }] },
    });
    const inv = await db.containerState.findUnique({
      where: { profileId_container: { profileId, container: "INVENTORY" } },
    });
    expect(inv?.value).toBe(1_500_000n);

    const samples = await db.bankValueSample.findMany({ where: { profileId } });
    expect(samples).toHaveLength(0);
  });

  it("BANK/INVENTORY/EQUIPMENT are independent containers", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "BANK",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { items: [{ id: 995, qty: 1 }] },
    });
    await materializeEvent(profileId, {
      type: "EQUIPMENT",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { items: [{ id: 4151, qty: 1 }] },
    });
    const containers = await db.containerState.findMany({ where: { profileId } });
    expect(containers.map((c) => c.container).sort()).toEqual(["BANK", "EQUIPMENT"]);
  });
});

describe("materializeEvent — KILL_COUNT", () => {
  it("upserts current kc and a daily kc sample", async () => {
    const profileId = await createTestProfile();
    await materializeEvent(profileId, {
      type: "KILL_COUNT",
      occurredAt: "2025-06-15T12:00:00Z",
      payload: { boss: "zulrah", kc: 42 },
    });
    const kc = await db.killCountState.findUnique({ where: { profileId_boss: { profileId, boss: "zulrah" } } });
    expect(kc?.kc).toBe(42);
    const sample = await db.kcSample.findUnique({
      where: { profileId_boss_date: { profileId, boss: "zulrah", date: dayOf(new Date("2025-06-15T12:00:00Z")) } },
    });
    expect(sample?.kc).toBe(42);
  });
});

describe("materializeEvent — unknown types", () => {
  it("is a no-op for LOGIN/LEVEL_UP style events (log-only)", async () => {
    const profileId = await createTestProfile();
    await expect(
      materializeEvent(profileId, { type: "LOGIN", occurredAt: "2025-06-15T12:00:00Z", payload: {} }),
    ).resolves.toBeUndefined();
  });
});

describe("ingestEvents — idempotency and batching", () => {
  it("appends to the event log and returns the ingested count", async () => {
    const profileId = await createTestProfile();
    const ingested = await ingestEvents(profileId, [
      { type: "KILL_COUNT", occurredAt: "2025-06-15T12:00:00Z", dedupeKey: "a", payload: { boss: "zulrah", kc: 1 } },
      { type: "KILL_COUNT", occurredAt: "2025-06-15T12:05:00Z", dedupeKey: "b", payload: { boss: "zulrah", kc: 2 } },
    ]);
    expect(ingested).toBe(2);
    const events = await db.event.findMany({ where: { profileId } });
    expect(events).toHaveLength(2);
  });

  it("skips events whose dedupeKey was already ingested (idempotent retry)", async () => {
    const profileId = await createTestProfile();
    const batch = [
      { type: "KILL_COUNT", occurredAt: "2025-06-15T12:00:00Z", dedupeKey: "dup-1", payload: { boss: "vorkath", kc: 5 } },
    ];
    const first = await ingestEvents(profileId, batch);
    const second = await ingestEvents(profileId, batch); // simulate a retried batch
    expect(first).toBe(1);
    expect(second).toBe(0);

    const events = await db.event.findMany({ where: { profileId } });
    expect(events).toHaveLength(1);
    const kc = await db.killCountState.findUnique({ where: { profileId_boss: { profileId, boss: "vorkath" } } });
    expect(kc?.kc).toBe(5);
  });

  it("events without a dedupeKey are always ingested", async () => {
    const profileId = await createTestProfile();
    const event = { type: "KILL_COUNT", occurredAt: "2025-06-15T12:00:00Z", payload: { boss: "kraken", kc: 1 } };
    const first = await ingestEvents(profileId, [event]);
    const second = await ingestEvents(profileId, [event]);
    expect(first).toBe(1);
    expect(second).toBe(1);
    const events = await db.event.findMany({ where: { profileId } });
    expect(events).toHaveLength(2);
  });

  it("a dedupeKey collision does not block later distinct events in the same batch", async () => {
    const profileId = await createTestProfile();
    await ingestEvents(profileId, [
      { type: "KILL_COUNT", occurredAt: "2025-06-15T12:00:00Z", dedupeKey: "shared", payload: { boss: "zulrah", kc: 1 } },
    ]);
    const ingested = await ingestEvents(profileId, [
      { type: "KILL_COUNT", occurredAt: "2025-06-15T12:00:00Z", dedupeKey: "shared", payload: { boss: "zulrah", kc: 1 } },
      { type: "KILL_COUNT", occurredAt: "2025-06-15T12:01:00Z", dedupeKey: "new", payload: { boss: "zulrah", kc: 2 } },
    ]);
    expect(ingested).toBe(1);
    const kc = await db.killCountState.findUnique({ where: { profileId_boss: { profileId, boss: "zulrah" } } });
    expect(kc?.kc).toBe(2);
  });
});
