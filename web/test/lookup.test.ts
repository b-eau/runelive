import { describe, expect, it } from "vitest";
import { normalizeUsername, parseHiscores } from "@/lib/lookup";
import hiscoresFixture from "./fixtures-hiscores.json";

describe("normalizeUsername", () => {
  it("accepts valid RSNs", () => {
    expect(normalizeUsername("beaumitch")).toBe("beaumitch");
    expect(normalizeUsername("Iron Gibby")).toBe("Iron Gibby");
    expect(normalizeUsername("a1_b-c")).toBe("a1_b-c");
  });

  it("collapses runs of whitespace and trims", () => {
    expect(normalizeUsername("  lynx   titan ")).toBe("lynx titan");
  });

  it("rejects invalid names", () => {
    expect(normalizeUsername("")).toBeNull();
    expect(normalizeUsername("!!bad!!")).toBeNull();
    expect(normalizeUsername("thisnameiswaytoolong")).toBeNull();
    expect(normalizeUsername(" leading-symbol-ok-but-not-space-start")).toBeNull();
    expect(normalizeUsername("-starts-bad")).toBeNull();
  });
});

describe("parseHiscores (real captured index_lite.json payload)", () => {
  const snapshot = parseHiscores("beaumitch", hiscoresFixture);

  it("extracts overall into totals and excludes it from skills", () => {
    expect(snapshot.totalLevel).toBe(1909);
    expect(snapshot.totalXp).toBeGreaterThan(90_000_000);
    expect(snapshot.skills.find((s) => s.skill === "overall")).toBeUndefined();
    expect(snapshot.skills).toHaveLength(24);
  });

  it("renames Runecraft to runecrafting to match our skill keys", () => {
    expect(snapshot.skills.find((s) => s.skill === "runecrafting")).toBeDefined();
    expect(snapshot.skills.find((s) => s.skill === "runecraft")).toBeUndefined();
  });

  it("splits activities into bosses vs non-boss activities", () => {
    const bossNames = snapshot.bosses.map((b) => b.name);
    expect(bossNames).toContain("Kraken");
    expect(bossNames).toContain("Zulrah");
    // Clues / minigames must NOT be treated as bosses
    expect(bossNames.find((n) => n.startsWith("Clue Scrolls"))).toBeUndefined();
    expect(bossNames.find((n) => n.startsWith("Collections"))).toBeUndefined();
    expect(snapshot.activities.find((a) => a.name.startsWith("Clue Scrolls"))).toBeDefined();
  });

  it("sorts bosses by kill count descending", () => {
    const kcs = snapshot.bosses.map((b) => b.kc);
    expect(kcs).toEqual([...kcs].sort((a, b) => b - a));
    expect(snapshot.bosses[0]).toEqual({ name: "Kraken", kc: 179 });
  });

  it("derives a combat level from the parsed skills", () => {
    expect(snapshot.combatLevel).toBe(119);
  });

  it("marks the source and leaves accountType unknown", () => {
    expect(snapshot.source).toBe("hiscores");
    expect(snapshot.accountType).toBeNull();
  });

  it("drops zero-score activities entirely", () => {
    for (const b of snapshot.bosses) expect(b.kc).toBeGreaterThan(0);
    for (const a of snapshot.activities) expect(a.score).toBeGreaterThan(0);
  });
});

describe("parseHiscores — unranked skills", () => {
  it("recomputes level from clamped xp when the API reports -1/unranked", () => {
    const snapshot = parseHiscores("newbie", {
      skills: [
        { id: 0, name: "Overall", rank: -1, level: 34, xp: 1154 },
        { id: 1, name: "Attack", rank: -1, level: 0, xp: -1 },
        { id: 5, name: "Ranged", rank: 12345, level: 40, xp: 37224 },
      ],
      activities: [],
    });
    const attack = snapshot.skills.find((s) => s.skill === "attack");
    expect(attack).toMatchObject({ xp: 0, level: 1, rank: undefined });
    const ranged = snapshot.skills.find((s) => s.skill === "ranged");
    expect(ranged).toMatchObject({ xp: 37224, level: 40, rank: 12345 });
  });
});
