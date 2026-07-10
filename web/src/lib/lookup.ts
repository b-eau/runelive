// Public player lookup for the guest ("try it") experience. Primary source
// is the Wise Old Man API; the official OSRS hiscores are the fallback when
// WOM is unreachable, rate-limits us, or has never seen the player.
//
// Guests never touch the database — results are normalized into a
// GuestSnapshot and held in a small in-memory TTL cache. Set
// GUEST_FIXTURES=1 to serve a deterministic built-in snapshot (used by the
// Playwright suite so E2E runs make no external network calls).

import { levelForXp } from "./osrs";

export type GuestSkill = { skill: string; xp: number; level: number; rank?: number };
export type GuestBoss = { name: string; kc: number };
export type GuestActivity = { name: string; score: number };

export type GuestSnapshot = {
  username: string;
  displayName: string;
  accountType: string | null; // REGULAR | IRONMAN | ... | null when unknown
  combatLevel: number | null;
  source: "wiseoldman" | "hiscores" | "fixture";
  totalLevel: number;
  totalXp: number;
  skills: GuestSkill[]; // excludes overall
  bosses: GuestBoss[];
  activities: GuestActivity[];
  fetchedAt: string;
};

const USER_AGENT =
  "OSRS-Sidekick/1.0 (guest lookup; https://github.com/b-eau/runelive)";
const FETCH_TIMEOUT_MS = 8_000;
const CACHE_TTL_MS = 10 * 60 * 1000;

const cache = new Map<string, { snapshot: GuestSnapshot; expiresAt: number }>();

export class PlayerNotFoundError extends Error {
  constructor(username: string) {
    super(`No hiscores entry found for "${username}"`);
    this.name = "PlayerNotFoundError";
  }
}

export function normalizeUsername(raw: string): string | null {
  const name = raw.trim().replace(/\s+/g, " ");
  // RSN rules: 1-12 chars, alphanumeric + spaces/hyphens/underscores.
  if (!/^[A-Za-z0-9][A-Za-z0-9 _-]{0,11}$/.test(name)) return null;
  return name;
}

async function fetchJson(url: string): Promise<unknown> {
  const res = await fetch(url, {
    headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
  if (res.status === 404) throw new PlayerNotFoundError(url);
  if (!res.ok) throw new Error(`${url} -> ${res.status}`);
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch {
    // Cloudflare challenge pages and the like arrive as HTML with a 200.
    throw new Error(`${url} returned non-JSON`);
  }
}

// ------------------------------------------------------------ Wise Old Man

const WOM_TYPE_MAP: Record<string, string> = {
  regular: "REGULAR",
  ironman: "IRONMAN",
  hardcore: "HARDCORE_IRONMAN",
  ultimate: "ULTIMATE_IRONMAN",
};

type WomPlayer = {
  displayName: string;
  type: string;
  combatLevel: number;
  latestSnapshot: {
    data: {
      skills: Record<string, { experience: number; level: number; rank: number }>;
      bosses: Record<string, { kills: number }>;
      activities: Record<string, { score: number }>;
    };
  } | null;
};

async function fetchWiseOldMan(username: string): Promise<GuestSnapshot> {
  const url = `https://api.wiseoldman.net/v2/players/${encodeURIComponent(username.toLowerCase())}`;
  const player = (await fetchJson(url)) as WomPlayer;
  const data = player.latestSnapshot?.data;
  if (!data) throw new Error("WOM has no snapshot for this player");

  const skills: GuestSkill[] = [];
  let totalLevel = 0;
  let totalXp = 0;
  for (const [skill, v] of Object.entries(data.skills)) {
    if (skill === "overall") {
      totalLevel = v.level;
      totalXp = v.experience;
      continue;
    }
    skills.push({ skill, xp: Math.max(0, v.experience), level: v.level, rank: v.rank });
  }

  const bosses = Object.entries(data.bosses)
    .filter(([, v]) => v.kills > 0)
    .map(([name, v]) => ({ name: name.replace(/_/g, " "), kc: v.kills }))
    .sort((a, b) => b.kc - a.kc);

  const activities = Object.entries(data.activities)
    .filter(([, v]) => v.score > 0)
    .map(([name, v]) => ({ name: name.replace(/_/g, " "), score: v.score }));

  return {
    username: username.toLowerCase(),
    displayName: player.displayName,
    accountType: WOM_TYPE_MAP[player.type] ?? null,
    combatLevel: player.combatLevel ?? null,
    source: "wiseoldman",
    totalLevel,
    totalXp,
    skills,
    bosses,
    activities,
    fetchedAt: new Date().toISOString(),
  };
}

// -------------------------------------------------------- official hiscores

// Hiscores "activities" mixes minigames/clues with bosses; these prefixes are
// the non-boss entries we surface separately.
const NON_BOSS_PREFIXES = [
  "Clue Scrolls",
  "PvP Arena",
  "Rifts closed",
  "Collections Logged",
  "Colosseum Glory",
  "League Points",
  "Deadman Points",
  "Bounty Hunter",
  "LMS",
  "Soul Wars",
  "GOTR",
];

const HISCORES_SKILL_RENAMES: Record<string, string> = {
  runecraft: "runecrafting",
};

type HiscoresLite = {
  skills: { id: number; name: string; rank: number; level: number; xp: number }[];
  activities: { id: number; name: string; rank: number; score: number }[];
};

export function parseHiscores(username: string, data: HiscoresLite): GuestSnapshot {
  const skills: GuestSkill[] = [];
  let totalLevel = 0;
  let totalXp = 0;
  for (const s of data.skills) {
    const xp = Math.max(0, s.xp);
    if (s.name === "Overall") {
      totalLevel = s.level;
      totalXp = xp;
      continue;
    }
    const key = s.name.toLowerCase();
    skills.push({
      skill: HISCORES_SKILL_RENAMES[key] ?? key,
      xp,
      // Unranked skills come back as level 1 / xp -1; recompute from xp so a
      // clamped 0 xp shows level 1 consistently.
      level: s.level > 0 ? s.level : levelForXp(xp),
      rank: s.rank > 0 ? s.rank : undefined,
    });
  }

  const bosses: GuestBoss[] = [];
  const activities: GuestActivity[] = [];
  for (const a of data.activities) {
    if (a.score <= 0) continue;
    if (NON_BOSS_PREFIXES.some((p) => a.name.startsWith(p))) {
      activities.push({ name: a.name, score: a.score });
    } else {
      bosses.push({ name: a.name, kc: a.score });
    }
  }
  bosses.sort((a, b) => b.kc - a.kc);

  return {
    username: username.toLowerCase(),
    displayName: username,
    accountType: null, // the standard hiscores endpoint doesn't reveal it
    combatLevel: combatFromSkills(skills),
    source: "hiscores",
    totalLevel,
    totalXp,
    skills,
    bosses,
    activities,
    fetchedAt: new Date().toISOString(),
  };
}

function combatFromSkills(skills: GuestSkill[]): number | null {
  const lvl = (name: string) => skills.find((s) => s.skill === name)?.level;
  const [att, str, def, hp, pray, range, mage] = [
    lvl("attack"), lvl("strength"), lvl("defence"), lvl("hitpoints"),
    lvl("prayer"), lvl("ranged"), lvl("magic"),
  ];
  if ([att, str, def, hp, pray, range, mage].some((v) => v === undefined)) return null;
  const base = 0.25 * (def! + hp! + Math.floor(pray! / 2));
  const melee = 0.325 * (att! + str!);
  const ranged = 0.325 * Math.floor(range! * 1.5);
  const magic = 0.325 * Math.floor(mage! * 1.5);
  return Math.floor(base + Math.max(melee, ranged, magic));
}

async function fetchHiscores(username: string): Promise<GuestSnapshot> {
  const url = `https://secure.runescape.com/m=hiscore_oldschool/index_lite.json?player=${encodeURIComponent(username)}`;
  const data = (await fetchJson(url)) as HiscoresLite;
  if (!Array.isArray(data.skills) || data.skills.length === 0) {
    throw new PlayerNotFoundError(username);
  }
  return parseHiscores(username, data);
}

// ----------------------------------------------------------------- fixture

function fixtureSnapshot(username: string): GuestSnapshot {
  const mk = (skill: string, xp: number): GuestSkill => ({ skill, xp, level: levelForXp(xp) });
  const skills = [
    mk("attack", 9_395_239), mk("defence", 5_411_734), mk("strength", 12_173_060),
    mk("hitpoints", 12_536_888), mk("ranged", 11_530_547), mk("prayer", 1_960_477),
    mk("magic", 6_333_117), mk("cooking", 925_073), mk("woodcutting", 1_100_715),
    mk("fletching", 737_870), mk("fishing", 687_374), mk("firemaking", 1_212_382),
    mk("crafting", 950_636), mk("smithing", 1_106_750), mk("mining", 1_143_184),
    mk("herblore", 872_126), mk("agility", 1_219_478), mk("thieving", 1_248_922),
    mk("slayer", 13_036_888), mk("farming", 1_700_756), mk("runecrafting", 552_473),
    mk("hunter", 781_141), mk("construction", 3_020_566), mk("sailing", 203_523),
  ];
  return {
    username: username.toLowerCase(),
    displayName: username,
    accountType: "REGULAR",
    combatLevel: 119,
    source: "fixture",
    totalLevel: skills.reduce((a, s) => a + s.level, 0),
    totalXp: skills.reduce((a, s) => a + s.xp, 0),
    skills,
    bosses: [
      { name: "kraken", kc: 179 },
      { name: "vorkath", kc: 57 },
      { name: "king black dragon", kc: 32 },
      { name: "tztok jad", kc: 28 },
      { name: "zulrah", kc: 8 },
    ],
    activities: [{ name: "Collections Logged", score: 126 }],
    fetchedAt: new Date().toISOString(),
  };
}

// ------------------------------------------------------------------ lookup

export async function lookupPlayer(rawUsername: string): Promise<GuestSnapshot> {
  const username = normalizeUsername(rawUsername);
  if (!username) throw new PlayerNotFoundError(rawUsername);

  if (process.env.GUEST_FIXTURES === "1") {
    return fixtureSnapshot(username);
  }

  const key = username.toLowerCase();
  const hit = cache.get(key);
  if (hit && hit.expiresAt > Date.now()) return hit.snapshot;

  let snapshot: GuestSnapshot;
  try {
    snapshot = await fetchWiseOldMan(username);
  } catch (womError) {
    if (womError instanceof PlayerNotFoundError) {
      // WOM has never tracked them — the official hiscores may still know them.
      snapshot = await fetchHiscores(username);
    } else {
      console.warn(`WOM lookup failed for ${username}, falling back to hiscores:`, womError);
      snapshot = await fetchHiscores(username);
    }
  }

  cache.set(key, { snapshot, expiresAt: Date.now() + CACHE_TTL_MS });
  // Bound the cache so a scan of usernames can't grow it without limit.
  if (cache.size > 2000) {
    const oldest = cache.keys().next().value;
    if (oldest) cache.delete(oldest);
  }
  return snapshot;
}
