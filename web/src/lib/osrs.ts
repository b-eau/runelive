// Static OSRS domain data: skill list, level curve, formatting.

export const SKILLS = [
  "attack",
  "strength",
  "defence",
  "ranged",
  "prayer",
  "magic",
  "runecrafting",
  "construction",
  "hitpoints",
  "agility",
  "herblore",
  "thieving",
  "crafting",
  "fletching",
  "slayer",
  "hunter",
  "mining",
  "smithing",
  "fishing",
  "cooking",
  "firemaking",
  "woodcutting",
  "farming",
  "sailing",
] as const;

export type Skill = (typeof SKILLS)[number];

export const COMBAT_SKILLS: Skill[] = [
  "attack",
  "strength",
  "defence",
  "ranged",
  "prayer",
  "magic",
  "hitpoints",
];

// XP required for each level 1..99 (index = level, XP_TABLE[1] = 0).
export const XP_TABLE: number[] = (() => {
  const t = [0, 0];
  let points = 0;
  for (let lvl = 1; lvl < 126; lvl++) {
    points += Math.floor(lvl + 300 * Math.pow(2, lvl / 7));
    t.push(Math.floor(points / 4));
  }
  return t;
})();

export function levelForXp(xp: number): number {
  let level = 1;
  for (let l = 2; l <= 99; l++) {
    if (xp >= XP_TABLE[l]) level = l;
    else break;
  }
  return level;
}

export function xpForLevel(level: number): number {
  return XP_TABLE[Math.min(Math.max(level, 1), 126)];
}

/** Progress within the current level, 0..1. Level 99+ returns 1. */
export function levelProgress(xp: number): number {
  const level = levelForXp(xp);
  if (level >= 99) return 1;
  const cur = XP_TABLE[level];
  const next = XP_TABLE[level + 1];
  return (xp - cur) / (next - cur);
}

export function combatLevel(levels: Partial<Record<Skill, number>>): number {
  const att = levels.attack ?? 1;
  const str = levels.strength ?? 1;
  const def = levels.defence ?? 1;
  const hp = levels.hitpoints ?? 10;
  const pray = levels.prayer ?? 1;
  const range = levels.ranged ?? 1;
  const mage = levels.magic ?? 1;
  const base = 0.25 * (def + hp + Math.floor(pray / 2));
  const melee = 0.325 * (att + str);
  const ranged = 0.325 * Math.floor(range * 1.5);
  const magic = 0.325 * Math.floor(mage * 1.5);
  return Math.floor(base + Math.max(melee, ranged, magic));
}

export function formatXp(xp: number | bigint): string {
  const n = Number(xp);
  if (n >= 1_000_000_000) return (n / 1_000_000_000).toFixed(2) + "B";
  if (n >= 10_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + "M";
  if (n >= 10_000) return (n / 1_000).toFixed(0) + "K";
  return n.toLocaleString("en-US");
}

export function formatGp(gp: number | bigint): string {
  return formatXp(gp);
}

export function titleCase(s: string): string {
  return s
    .split(/[_\s]+/)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

export const PROFILE_KINDS = ["STANDARD", "LEAGUES", "DEADMAN", "TOURNAMENT", "BETA"] as const;

export const ACCOUNT_TYPE_LABELS: Record<string, string> = {
  REGULAR: "Regular",
  IRONMAN: "Ironman",
  HARDCORE_IRONMAN: "Hardcore Ironman",
  ULTIMATE_IRONMAN: "Ultimate Ironman",
  GROUP_IRONMAN: "Group Ironman",
  HARDCORE_GROUP_IRONMAN: "Hardcore Group Ironman",
  UNRANKED_GROUP_IRONMAN: "Unranked Group Ironman",
};

export const PROFILE_KIND_LABELS: Record<string, string> = {
  STANDARD: "Main game",
  LEAGUES: "Leagues",
  DEADMAN: "Deadman Mode",
  TOURNAMENT: "Tournament",
  BETA: "Beta",
};
