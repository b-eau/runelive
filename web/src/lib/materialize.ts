// Event materialization: turns append-only plugin events into fast
// current-state rows and daily rollup samples. Runs synchronously inside
// the ingest request today; the same function can be moved behind a queue
// worker without changing callers (see ARCHITECTURE.md).

import { Prisma } from "@prisma/client";
import { db } from "./db";
import { levelForXp, combatLevel, type Skill } from "./osrs";

export type IngestEvent = {
  type: string;
  occurredAt: string; // ISO timestamp from the client
  dedupeKey?: string;
  payload: unknown;
};

export type SkillsPayload = { skills: Record<string, { xp: number; level?: number }> };
export type QuestsPayload = { quests: { name: string; state: string }[] };
export type DiariesPayload = { diaries: { area: string; tier: string; completed: boolean }[] };
export type ContainerPayload = { items: { id: number; qty: number; name?: string }[] };
export type KillCountPayload = { boss: string; kc: number };
export type CombatAchievementsPayload = { points: number; thresholds: Record<string, number> };
export type CollectionLogPayload = {
  obtained: number;
  total: number;
  sections?: Record<string, { obtained: number; total: number }>;
};
export type CollectionLogItemsPayload = { obtained: number[]; universe: number[] };

/** UTC midnight for the rollup grain. */
export function dayOf(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
}

export async function priceItems(items: { id: number; qty: number }[]): Promise<bigint> {
  if (items.length === 0) return 0n;
  const prices = await db.itemPrice.findMany({
    where: { itemId: { in: items.map((i) => i.id) } },
  });
  const byId = new Map(prices.map((p) => [p.itemId, p.price]));
  let total = 0n;
  for (const item of items) {
    const price = byId.get(item.id);
    // Coins are worth face value; unknown items are valued at 0.
    const unit = item.id === 995 ? 1 : (price ?? 0);
    total += BigInt(unit) * BigInt(item.qty);
  }
  return total;
}

export async function materializeEvent(profileId: string, event: IngestEvent): Promise<void> {
  const occurredAt = new Date(event.occurredAt);
  const day = dayOf(occurredAt);

  switch (event.type) {
    case "SKILLS": {
      const { skills } = event.payload as SkillsPayload;
      const levels: Partial<Record<Skill, number>> = {};
      for (const [skill, v] of Object.entries(skills)) {
        const xp = BigInt(Math.max(0, Math.floor(v.xp)));
        const level = v.level ?? levelForXp(Number(xp));
        if (skill !== "overall") levels[skill as Skill] = level;
        await db.skillState.upsert({
          where: { profileId_skill: { profileId, skill } },
          update: { xp, level, updatedAt: occurredAt },
          create: { profileId, skill, xp, level, updatedAt: occurredAt },
        });
        await db.xpSample.upsert({
          where: { profileId_skill_date: { profileId, skill, date: day } },
          update: { xp },
          create: { profileId, skill, date: day, xp },
        });
      }
      // Maintain a synthetic "overall" row if the client didn't send one.
      if (!skills.overall) {
        const rows = await db.skillState.findMany({
          where: { profileId, skill: { not: "overall" } },
        });
        const totalXp = rows.reduce((acc, r) => acc + r.xp, 0n);
        const totalLevel = rows.reduce((acc, r) => acc + r.level, 0);
        await db.skillState.upsert({
          where: { profileId_skill: { profileId, skill: "overall" } },
          update: { xp: totalXp, level: totalLevel, updatedAt: occurredAt },
          create: { profileId, skill: "overall", xp: totalXp, level: totalLevel, updatedAt: occurredAt },
        });
        await db.xpSample.upsert({
          where: { profileId_skill_date: { profileId, skill: "overall", date: day } },
          update: { xp: totalXp },
          create: { profileId, skill: "overall", date: day, xp: totalXp },
        });
      }
      if (Object.keys(levels).length >= 7) {
        await db.profile.update({
          where: { id: profileId },
          data: { combatLevel: combatLevel(levels), lastSyncedAt: new Date() },
        });
      } else {
        await db.profile.update({ where: { id: profileId }, data: { lastSyncedAt: new Date() } });
      }
      break;
    }

    case "QUESTS": {
      const { quests } = event.payload as QuestsPayload;
      for (const q of quests) {
        await db.questState.upsert({
          where: { profileId_quest: { profileId, quest: q.name } },
          update: { state: q.state, updatedAt: occurredAt },
          create: { profileId, quest: q.name, state: q.state, updatedAt: occurredAt },
        });
      }
      break;
    }

    case "DIARIES": {
      const { diaries } = event.payload as DiariesPayload;
      for (const d of diaries) {
        await db.diaryState.upsert({
          where: { profileId_area_tier: { profileId, area: d.area, tier: d.tier } },
          update: { completed: d.completed, updatedAt: occurredAt },
          create: { profileId, area: d.area, tier: d.tier, completed: d.completed, updatedAt: occurredAt },
        });
      }
      break;
    }

    case "BANK":
    case "INVENTORY":
    case "EQUIPMENT": {
      const { items } = event.payload as ContainerPayload;
      const value = await priceItems(items);
      await db.containerState.upsert({
        where: { profileId_container: { profileId, container: event.type } },
        update: { items: JSON.stringify(items), value, updatedAt: occurredAt },
        create: { profileId, container: event.type, items: JSON.stringify(items), value, updatedAt: occurredAt },
      });
      if (event.type === "BANK") {
        await db.bankValueSample.upsert({
          where: { profileId_date: { profileId, date: day } },
          update: { value },
          create: { profileId, date: day, value },
        });
      }
      break;
    }

    case "KILL_COUNT": {
      const { boss, kc } = event.payload as KillCountPayload;
      await db.killCountState.upsert({
        where: { profileId_boss: { profileId, boss } },
        update: { kc, updatedAt: occurredAt },
        create: { profileId, boss, kc, updatedAt: occurredAt },
      });
      await db.kcSample.upsert({
        where: { profileId_boss_date: { profileId, boss, date: day } },
        update: { kc },
        create: { profileId, boss, date: day, kc },
      });
      break;
    }

    case "COMBAT_ACHIEVEMENTS": {
      const { points, thresholds } = event.payload as CombatAchievementsPayload;
      // A tier is complete once points reach its threshold; a threshold of 0
      // means the client hadn't loaded it, so never mark that tier done.
      const done = (tier: string) => {
        const t = thresholds?.[tier] ?? 0;
        return t > 0 && points >= t;
      };
      const tiers = {
        points,
        thresholds: JSON.stringify(thresholds ?? {}),
        easy: done("EASY"),
        medium: done("MEDIUM"),
        hard: done("HARD"),
        elite: done("ELITE"),
        master: done("MASTER"),
        grandmaster: done("GRANDMASTER"),
        updatedAt: occurredAt,
      };
      await db.combatAchievementState.upsert({
        where: { profileId },
        update: tiers,
        create: { profileId, ...tiers },
      });
      break;
    }

    case "COLLECTION_LOG": {
      const { obtained, total, sections } = event.payload as CollectionLogPayload;
      if (!(total > 0)) break; // unsynced counts — keep whatever we have
      const data = {
        obtained: Math.max(0, obtained),
        total,
        sections: JSON.stringify(sections ?? {}),
        updatedAt: occurredAt,
      };
      await db.collectionLogState.upsert({
        where: { profileId },
        update: data,
        create: { profileId, ...data },
      });
      break;
    }

    case "COLLECTION_LOG_ITEMS": {
      const { obtained, universe } = event.payload as CollectionLogItemsPayload;
      if (!Array.isArray(universe) || universe.length === 0) break;
      const obtainedSet = new Set(Array.isArray(obtained) ? obtained : []);
      // Full replace: each sync carries the complete slot universe.
      await db.collectionLogSlot.deleteMany({ where: { profileId } });
      await db.collectionLogSlot.createMany({
        data: universe
          .filter((id) => Number.isInteger(id))
          .map((itemId) => ({ profileId, itemId, obtained: obtainedSet.has(itemId), updatedAt: occurredAt })),
        skipDuplicates: true,
      });
      break;
    }

    // LOGIN / LEVEL_UP and unknown types are kept in the event log only.
    default:
      break;
  }
}

/**
 * Appends a batch of events for a profile and materializes them.
 * Events whose dedupeKey was already seen are skipped (idempotent retries).
 * Returns the number of newly ingested events.
 */
export async function ingestEvents(profileId: string, events: IngestEvent[]): Promise<number> {
  let ingested = 0;
  for (const event of events) {
    try {
      await db.event.create({
        data: {
          profileId,
          type: event.type,
          payload: JSON.stringify(event.payload ?? {}),
          dedupeKey: event.dedupeKey ?? null,
          occurredAt: new Date(event.occurredAt),
        },
      });
    } catch (e) {
      if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === "P2002") {
        continue; // duplicate dedupeKey — already ingested
      }
      throw e;
    }
    await materializeEvent(profileId, event);
    ingested++;
  }
  return ingested;
}
