// Seeds a demo user (beaumitch@gmail.com) owning OSRS account "dummymitch"
// whose stats mirror the real Wise Old Man player "beaumitch"
// (prisma/seed-data/*.json). Generates ~18 months of daily XP / bank value /
// KC history so trend charts and analytics have realistic data, plus a
// second LEAGUES profile to demonstrate multi-profile accounts.

import { PrismaClient } from "@prisma/client";
import { createHash } from "crypto";
import womPlayer from "./seed-data/wom-player.json";
import womSnapshots from "./seed-data/wom-snapshots.json";

const db = new PrismaClient();

const DAY = 24 * 60 * 60 * 1000;
const HISTORY_DAYS = 550;

// Deterministic PRNG so reseeding produces identical data.
function mulberry32(seed: number) {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function dayOf(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
}

function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

type WomSkillEntry = { experience: number; level: number };
type WomSnapshot = { createdAt: string; data: { skills: Record<string, WomSkillEntry> } };

// Rough GE guide prices (gp) for the demo bank. Live deployments refresh
// ItemPrice from the OSRS wiki prices API (npm run prices:sync).
const ITEM_PRICES: { itemId: number; name: string; price: number }[] = [
  { itemId: 995, name: "Coins", price: 1 },
  { itemId: 4151, name: "Abyssal whip", price: 1_620_000 },
  { itemId: 11832, name: "Bandos chestplate", price: 14_500_000 },
  { itemId: 11834, name: "Bandos tassets", price: 17_800_000 },
  { itemId: 6585, name: "Amulet of fury", price: 2_050_000 },
  { itemId: 4587, name: "Dragon scimitar", price: 59_000 },
  { itemId: 11840, name: "Dragon boots", price: 165_000 },
  { itemId: 6570, name: "Fire cape", price: 0 },
  { itemId: 2434, name: "Prayer potion(4)", price: 9_800 },
  { itemId: 3024, name: "Super restore(4)", price: 11_400 },
  { itemId: 385, name: "Shark", price: 900 },
  { itemId: 13441, name: "Anglerfish", price: 1_650 },
  { itemId: 560, name: "Death rune", price: 205 },
  { itemId: 565, name: "Blood rune", price: 210 },
  { itemId: 9075, name: "Astral rune", price: 145 },
  { itemId: 561, name: "Nature rune", price: 175 },
  { itemId: 811, name: "Rune dart", price: 320 },
  { itemId: 892, name: "Rune arrow", price: 65 },
  { itemId: 11212, name: "Dragon arrow", price: 1_450 },
  { itemId: 12934, name: "Zulrah's scales", price: 95 },
  { itemId: 13576, name: "Dragon warhammer", price: 22_000_000 },
  { itemId: 12002, name: "Occult necklace", price: 380_000 },
  { itemId: 6737, name: "Berserker ring", price: 2_650_000 },
  { itemId: 6733, name: "Archers ring", price: 750_000 },
  { itemId: 11826, name: "Armadyl helmet", price: 5_100_000 },
  { itemId: 11828, name: "Armadyl chestplate", price: 28_500_000 },
  { itemId: 11830, name: "Armadyl chainskirt", price: 24_200_000 },
  { itemId: 12924, name: "Toxic blowpipe (empty)", price: 4_100_000 },
  { itemId: 2577, name: "Ranger boots", price: 28_000_000 },
  { itemId: 10828, name: "Helm of neitiznot", price: 42_000 },
  { itemId: 4708, name: "Ahrim's hood", price: 210_000 },
  { itemId: 4712, name: "Ahrim's robetop", price: 2_300_000 },
  { itemId: 4714, name: "Ahrim's robeskirt", price: 1_900_000 },
  { itemId: 4716, name: "Dharok's helm", price: 620_000 },
  { itemId: 4718, name: "Dharok's greataxe", price: 1_150_000 },
  { itemId: 4720, name: "Dharok's platebody", price: 1_450_000 },
  { itemId: 4722, name: "Dharok's platelegs", price: 1_320_000 },
  { itemId: 1513, name: "Magic logs", price: 1_020 },
  { itemId: 1515, name: "Yew logs", price: 260 },
  { itemId: 2357, name: "Gold bar", price: 95 },
  { itemId: 453, name: "Coal", price: 155 },
  { itemId: 451, name: "Runite ore", price: 11_100 },
  { itemId: 1631, name: "Uncut dragonstone", price: 12_800 },
  { itemId: 1391, name: "Battlestaff", price: 8_100 },
  { itemId: 5295, name: "Ranarr seed", price: 30_500 },
  { itemId: 5300, name: "Snapdragon seed", price: 47_500 },
  { itemId: 207, name: "Grimy ranarr weed", price: 6_600 },
  { itemId: 3000, name: "Grimy snapdragon", price: 8_900 },
  { itemId: 12695, name: "Super combat potion(4)", price: 14_800 },
  { itemId: 9244, name: "Dragon bolts (e)", price: 1_650 },
  { itemId: 536, name: "Dragon bones", price: 2_450 },
  { itemId: 22124, name: "Superior dragon bones", price: 8_200 },
  { itemId: 21880, name: "Wrath rune", price: 340 },
  { itemId: 19553, name: "Amulet of torture", price: 9_800_000 },
  { itemId: 19547, name: "Necklace of anguish", price: 8_900_000 },
  { itemId: 11785, name: "Armadyl crossbow", price: 32_000_000 },
  { itemId: 861, name: "Magic shortbow", price: 780 },
  { itemId: 12926, name: "Toxic blowpipe", price: 4_300_000 },
  { itemId: 13652, name: "Dragon claws", price: 68_000_000 },
  { itemId: 22981, name: "Ferocious gloves", price: 5_800_000 },
  { itemId: 7462, name: "Barrows gloves", price: 0 },
  { itemId: 11865, name: "Slayer helmet", price: 0 },
  { itemId: 12931, name: "Serpentine helm", price: 2_900_000 },
];

const BANK_ITEMS: { id: number; qty: number }[] = [
  { id: 995, qty: 23_450_000 },
  { id: 4151, qty: 1 },
  { id: 11832, qty: 1 },
  { id: 11834, qty: 1 },
  { id: 6585, qty: 1 },
  { id: 11840, qty: 1 },
  { id: 6570, qty: 1 },
  { id: 2434, qty: 148 },
  { id: 3024, qty: 92 },
  { id: 385, qty: 611 },
  { id: 13441, qty: 244 },
  { id: 560, qty: 8_420 },
  { id: 565, qty: 5_130 },
  { id: 9075, qty: 3_800 },
  { id: 561, qty: 2_240 },
  { id: 11212, qty: 320 },
  { id: 12934, qty: 18_500 },
  { id: 12002, qty: 1 },
  { id: 6737, qty: 1 },
  { id: 12924, qty: 1 },
  { id: 10828, qty: 1 },
  { id: 4716, qty: 1 },
  { id: 4718, qty: 1 },
  { id: 4720, qty: 1 },
  { id: 4722, qty: 1 },
  { id: 1513, qty: 1_240 },
  { id: 453, qty: 4_100 },
  { id: 451, qty: 210 },
  { id: 5295, qty: 84 },
  { id: 207, qty: 460 },
  { id: 12695, qty: 61 },
  { id: 536, qty: 1_180 },
  { id: 12931, qty: 1 },
  { id: 861, qty: 1 },
  { id: 7462, qty: 1 },
  { id: 11865, qty: 1 },
];

const EQUIPMENT_ITEMS: { id: number; qty: number }[] = [
  { id: 11865, qty: 1 }, // slayer helmet
  { id: 6570, qty: 1 }, // fire cape
  { id: 6585, qty: 1 }, // fury
  { id: 4151, qty: 1 }, // whip
  { id: 11832, qty: 1 }, // bcp
  { id: 11834, qty: 1 }, // tassets
  { id: 11840, qty: 1 }, // d boots
  { id: 7462, qty: 1 }, // barrows gloves
  { id: 6737, qty: 1 }, // b ring
];

const INVENTORY_ITEMS: { id: number; qty: number }[] = [
  { id: 995, qty: 302_419 },
  { id: 2434, qty: 4 },
  { id: 385, qty: 12 },
  { id: 12695, qty: 2 },
  { id: 4587, qty: 1 },
];

// Quests left unfinished for the demo profile (a near-quest-cape main).
const UNFINISHED = new Map<string, string>([
  ["Desert Treasure II - The Fallen Empire", "IN_PROGRESS"],
  ["Song of the Elves", "IN_PROGRESS"],
  ["Monkey Madness II", "FINISHED"],
  ["While Guthix Sleeps", "NOT_STARTED"],
  ["A Night at the Theatre", "NOT_STARTED"],
  ["The Curse of Arrav", "NOT_STARTED"],
  ["His Faithful Servants", "NOT_STARTED"],
  ["Death on the Isle", "IN_PROGRESS"],
  ["Ethically Acquired Antiquities", "NOT_STARTED"],
  ["Grim Tales", "IN_PROGRESS"],
]);

const DIARY_PROGRESS: Record<string, number> = {
  // number of tiers completed per area (EASY..ELITE)
  Ardougne: 3,
  Desert: 2,
  Falador: 3,
  Fremennik: 3,
  Kandarin: 3,
  Karamja: 3,
  "Kourend & Kebos": 2,
  "Lumbridge & Draynor": 4,
  Morytania: 3,
  Varrock: 3,
  "Western Provinces": 2,
  Wilderness: 1,
};

async function main() {
  const { ALL_QUESTS, DIARY_AREAS, DIARY_TIERS } = await import("../src/lib/quests");

  console.log("Seeding OSRS Sidekick demo data…");

  // Wipe in dependency order so the seed is rerunnable.
  await db.chatMessage.deleteMany();
  await db.goal.deleteMany();
  await db.kcSample.deleteMany();
  await db.killCountState.deleteMany();
  await db.bankValueSample.deleteMany();
  await db.containerState.deleteMany();
  await db.diaryState.deleteMany();
  await db.questState.deleteMany();
  await db.xpSample.deleteMany();
  await db.skillState.deleteMany();
  await db.event.deleteMany();
  await db.apiToken.deleteMany();
  await db.linkCode.deleteMany();
  await db.profile.deleteMany();
  await db.osrsAccount.deleteMany();
  await db.magicLink.deleteMany();
  await db.session.deleteMany();
  await db.user.deleteMany();
  await db.itemPrice.deleteMany();

  await db.itemPrice.createMany({ data: ITEM_PRICES });

  const user = await db.user.create({
    data: { email: "beaumitch@gmail.com", name: "Beau" },
  });

  const account = await db.osrsAccount.create({
    data: {
      userId: user.id,
      accountHash: "seed-dummymitch-0001",
      displayName: "dummymitch",
    },
  });

  // Stable dev API token so a plugin/curl can ingest against the seed account.
  const devToken = "dev-ingest-token-dummymitch";
  await db.apiToken.create({
    data: { token: hashToken(devToken), accountId: account.id },
  });

  const main = await db.profile.create({
    data: {
      accountId: account.id,
      kind: "STANDARD",
      accountType: "REGULAR",
      combatLevel: (womPlayer as { combatLevel: number }).combatLevel,
      lastSyncedAt: new Date(),
    },
  });

  const leagues = await db.profile.create({
    data: {
      accountId: account.id,
      kind: "LEAGUES",
      accountType: "IRONMAN",
      combatLevel: 83,
      lastSyncedAt: new Date(Date.now() - 40 * DAY),
    },
  });

  // ---------------------------------------------------------------- skills
  const snapshot = (womPlayer as { latestSnapshot: { data: { skills: Record<string, WomSkillEntry> } } })
    .latestSnapshot.data;
  const skills = snapshot.skills;

  const now = new Date();
  const today = dayOf(now);

  for (const [skill, v] of Object.entries(skills)) {
    await db.skillState.create({
      data: {
        profileId: main.id,
        skill,
        xp: BigInt(v.experience),
        level: v.level,
        updatedAt: now,
      },
    });
  }

  // ------------------------------------------------------------ xp history
  // Real WOM snapshots cover recent weeks; synthesize the rest of the
  // 550-day window per-skill with deterministic noise + grind bursts.
  const realSnapshots = (womSnapshots as WomSnapshot[])
    .slice()
    .sort((a, b) => +new Date(a.createdAt) - +new Date(b.createdAt));

  const skillNames = Object.keys(skills);
  const xpRows: { profileId: string; skill: string; date: Date; xp: bigint }[] = [];

  for (const skill of skillNames) {
    const rng = mulberry32(skillNames.indexOf(skill) * 7919 + 17);
    const currentXp = skills[skill].experience;
    const earliestReal = realSnapshots.length
      ? realSnapshots[0].data.skills[skill]?.experience ?? currentXp
      : currentXp;

    // Start the synthetic window at 45–70% of the earliest real value.
    const startFraction = 0.45 + rng() * 0.25;
    const startXp = Math.floor(earliestReal * startFraction);
    const realStartDay = realSnapshots.length
      ? dayOf(new Date(realSnapshots[0].createdAt))
      : today;
    const syntheticDays = Math.max(
      1,
      Math.floor((+realStartDay - (+today - HISTORY_DAYS * DAY)) / DAY),
    );

    // Random walk from startXp to earliestReal across syntheticDays.
    let xp = startXp;
    const totalGain = Math.max(0, earliestReal - startXp);
    // Weight gains into occasional "grind burst" periods.
    const weights: number[] = [];
    let weightSum = 0;
    for (let i = 0; i < syntheticDays; i++) {
      const burst = rng() < 0.08 ? 6 + rng() * 10 : rng() < 0.4 ? rng() : 0;
      weights.push(burst);
      weightSum += burst;
    }
    for (let i = 0; i < syntheticDays; i++) {
      const date = new Date(+today - (HISTORY_DAYS - i) * DAY);
      xp += weightSum > 0 ? Math.floor((totalGain * weights[i]) / weightSum) : 0;
      // Sample sparsely in the synthetic era (every ~3 days) to mimic
      // organic play patterns without bloating the table.
      if (i % 3 === 0 || weights[i] > 5) {
        xpRows.push({ profileId: main.id, skill, date, xp: BigInt(Math.min(xp, earliestReal)) });
      }
    }
    // Real snapshots (daily grain, last write per day wins).
    const byDay = new Map<number, number>();
    for (const s of realSnapshots) {
      const entry = s.data.skills[skill];
      if (!entry) continue;
      byDay.set(+dayOf(new Date(s.createdAt)), entry.experience);
    }
    for (const [ts, exp] of byDay) {
      xpRows.push({ profileId: main.id, skill, date: new Date(ts), xp: BigInt(exp) });
    }
    // Today's value = current state.
    xpRows.push({ profileId: main.id, skill, date: today, xp: BigInt(currentXp) });
  }

  // De-duplicate on (skill, date), last wins.
  const dedup = new Map<string, (typeof xpRows)[number]>();
  for (const row of xpRows) dedup.set(`${row.skill}|${+row.date}`, row);
  await db.xpSample.createMany({ data: [...dedup.values()] });

  // ---------------------------------------------------------------- quests
  for (const quest of ALL_QUESTS) {
    await db.questState.create({
      data: {
        profileId: main.id,
        quest,
        state: UNFINISHED.get(quest) ?? "FINISHED",
        updatedAt: now,
      },
    });
  }

  // --------------------------------------------------------------- diaries
  for (const area of DIARY_AREAS) {
    const done = DIARY_PROGRESS[area] ?? 0;
    for (let i = 0; i < DIARY_TIERS.length; i++) {
      await db.diaryState.create({
        data: {
          profileId: main.id,
          area,
          tier: DIARY_TIERS[i],
          completed: i < done,
          updatedAt: now,
        },
      });
    }
  }

  // ------------------------------------------------------------ containers
  const priceMap = new Map(ITEM_PRICES.map((p) => [p.itemId, p.price]));
  const valueOf = (items: { id: number; qty: number }[]) =>
    items.reduce((acc, i) => acc + BigInt(i.id === 995 ? 1 : priceMap.get(i.id) ?? 0) * BigInt(i.qty), 0n);

  const bankValue = valueOf(BANK_ITEMS);
  await db.containerState.createMany({
    data: [
      { profileId: main.id, container: "BANK", items: JSON.stringify(BANK_ITEMS), value: bankValue, updatedAt: now },
      { profileId: main.id, container: "EQUIPMENT", items: JSON.stringify(EQUIPMENT_ITEMS), value: valueOf(EQUIPMENT_ITEMS), updatedAt: now },
      { profileId: main.id, container: "INVENTORY", items: JSON.stringify(INVENTORY_ITEMS), value: valueOf(INVENTORY_ITEMS), updatedAt: now },
    ],
  });

  // Bank value trend: random walk ending at today's value.
  {
    const rng = mulberry32(4242);
    const rows: { profileId: string; date: Date; value: bigint }[] = [];
    let value = Number(bankValue) * (0.35 + rng() * 0.1);
    for (let i = HISTORY_DAYS; i >= 1; i -= 2) {
      const date = new Date(+today - i * DAY);
      const drift = (Number(bankValue) - value) / (i / 2 + 1);
      value += drift * (0.5 + rng()) + (rng() - 0.45) * 2_000_000;
      value = Math.max(1_000_000, value);
      rows.push({ profileId: main.id, date, value: BigInt(Math.floor(value)) });
    }
    rows.push({ profileId: main.id, date: today, value: bankValue });
    await db.bankValueSample.createMany({ data: rows });
  }

  // ------------------------------------------------------------------- kc
  const bosses = (womPlayer as { latestSnapshot: { data: { bosses: Record<string, { kills: number }> } } })
    .latestSnapshot.data.bosses;
  const kcRows: { profileId: string; boss: string; date: Date; kc: number }[] = [];
  for (const [boss, v] of Object.entries(bosses)) {
    if (v.kills <= 0) continue;
    await db.killCountState.create({
      data: { profileId: main.id, boss, kc: v.kills, updatedAt: now },
    });
    // Ramp KC from 0 to current over a boss-specific window.
    const rng = mulberry32(boss.length * 1543 + boss.charCodeAt(0));
    const windowDays = Math.min(HISTORY_DAYS, 60 + Math.floor(rng() * 400));
    let kc = 0;
    for (let i = windowDays; i >= 0; i -= 7) {
      const date = new Date(+today - i * DAY);
      const remainingSteps = Math.floor(i / 7) + 1;
      kc += Math.floor(((v.kills - kc) / remainingSteps) * (0.6 + rng() * 0.8));
      kc = Math.min(kc, v.kills);
      kcRows.push({ profileId: main.id, boss, date, kc: i === 0 ? v.kills : kc });
    }
  }
  const kcDedup = new Map<string, (typeof kcRows)[number]>();
  for (const row of kcRows) kcDedup.set(`${row.boss}|${+row.date}`, row);
  await db.kcSample.createMany({ data: [...kcDedup.values()] });

  // ---------------------------------------------------------------- goals
  await db.goal.createMany({
    data: [
      {
        profileId: main.id,
        title: "Untrim the Slayer cape",
        notes: "99 Slayer achieved — keep it untrimmed until 99 in a second skill? Nope: goal is to earn the untrimmed look by making Slayer the first 99. Focus future 99s afterwards.",
        status: "ACTIVE",
      },
      {
        profileId: main.id,
        title: "Quest cape",
        notes: "Finish Song of the Elves and Desert Treasure II. Prioritise AFK-friendly stat requirements first.",
        status: "ACTIVE",
      },
      {
        profileId: main.id,
        title: "Base 80 all skills",
        status: "ACTIVE",
      },
    ],
  });

  // --------------------------------------------------------------- leagues
  // A lightweight second profile to demonstrate the multi-profile model.
  const leaguesSkills: Record<string, number> = {
    attack: 2_200_000, strength: 2_600_000, defence: 1_100_000, hitpoints: 2_400_000,
    ranged: 3_900_000, prayer: 320_000, magic: 2_800_000, cooking: 210_000,
    woodcutting: 310_000, fletching: 150_000, fishing: 260_000, firemaking: 290_000,
    crafting: 240_000, smithing: 190_000, mining: 330_000, herblore: 160_000,
    agility: 410_000, thieving: 620_000, slayer: 1_450_000, farming: 120_000,
    runecrafting: 95_000, hunter: 210_000, construction: 130_000, sailing: 45_000,
  };
  const { levelForXp } = await import("../src/lib/osrs");
  let leaguesTotalXp = 0n;
  let leaguesTotalLvl = 0;
  for (const [skill, xp] of Object.entries(leaguesSkills)) {
    const level = levelForXp(xp);
    leaguesTotalXp += BigInt(xp);
    leaguesTotalLvl += level;
    await db.skillState.create({
      data: { profileId: leagues.id, skill, xp: BigInt(xp), level, updatedAt: new Date(Date.now() - 40 * DAY) },
    });
  }
  await db.skillState.create({
    data: { profileId: leagues.id, skill: "overall", xp: leaguesTotalXp, level: leaguesTotalLvl, updatedAt: new Date(Date.now() - 40 * DAY) },
  });
  await db.goal.create({
    data: { profileId: leagues.id, title: "Dragon Cup — finish in the top 10% of Leagues", status: "ACTIVE" },
  });

  const counts = {
    xpSamples: await db.xpSample.count(),
    kcSamples: await db.kcSample.count(),
    bankSamples: await db.bankValueSample.count(),
    quests: await db.questState.count(),
  };
  console.log("Seed complete:", counts);
  console.log(`Demo sign-in email: beaumitch@gmail.com (magic link prints to this console)`);
  console.log(`Dev ingest token:   ${devToken}`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => db.$disconnect());
