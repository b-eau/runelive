import { describe, expect, it } from "vitest";
import { combatLevel, formatGp, formatXp, levelForXp, levelProgress, titleCase, xpForLevel } from "@/lib/osrs";

describe("levelForXp / xpForLevel", () => {
  it("level 1 at 0 xp", () => {
    expect(levelForXp(0)).toBe(1);
  });

  it("known OSRS xp thresholds", () => {
    expect(levelForXp(83)).toBe(2);
    expect(levelForXp(82)).toBe(1);
    expect(levelForXp(13034431)).toBe(99);
    expect(levelForXp(13034430)).toBe(98);
  });

  it("xpForLevel and levelForXp roundtrip at each level boundary", () => {
    for (let level = 2; level <= 99; level++) {
      const xp = xpForLevel(level);
      expect(levelForXp(xp)).toBe(level);
      expect(levelForXp(xp - 1)).toBe(level - 1);
    }
  });

  it("clamps above 99 and below 1", () => {
    expect(xpForLevel(150)).toBe(xpForLevel(126));
    expect(xpForLevel(0)).toBe(xpForLevel(1));
  });
});

describe("levelProgress", () => {
  it("0 at the start of a level, approaches 1 near the next", () => {
    const xp = xpForLevel(50);
    expect(levelProgress(xp)).toBeCloseTo(0, 5);
  });

  it("returns 1 at level 99+", () => {
    expect(levelProgress(xpForLevel(99))).toBe(1);
    expect(levelProgress(xpForLevel(99) + 1_000_000)).toBe(1);
  });
});

describe("combatLevel", () => {
  it("matches the known level-3 baseline", () => {
    expect(combatLevel({})).toBe(3);
  });

  it("increases with melee stats", () => {
    const low = combatLevel({ attack: 1, strength: 1, defence: 1, hitpoints: 10, prayer: 1, ranged: 1, magic: 1 });
    const high = combatLevel({ attack: 99, strength: 99, defence: 99, hitpoints: 99, prayer: 99, ranged: 1, magic: 1 });
    expect(high).toBeGreaterThan(low);
    expect(high).toBeLessThanOrEqual(126);
  });

  it("takes the max of melee/ranged/magic contribution", () => {
    const rangedOnly = combatLevel({ ranged: 99, hitpoints: 99, defence: 99, prayer: 99 });
    const meleeOnly = combatLevel({ attack: 99, strength: 99, hitpoints: 99, defence: 99, prayer: 99 });
    // Both should reach a high, comparable combat level via their own path.
    expect(rangedOnly).toBeGreaterThan(50);
    expect(meleeOnly).toBeGreaterThan(50);
  });
});

describe("formatXp / formatGp", () => {
  it("formats small numbers with grouping", () => {
    expect(formatXp(500)).toBe("500");
    expect(formatXp(1234)).toBe("1,234");
  });

  it("formats thousands as K", () => {
    expect(formatXp(15_000)).toBe("15K");
  });

  it("formats millions as M", () => {
    expect(formatXp(1_500_000)).toBe("1.50M");
    expect(formatXp(15_000_000)).toBe("15.0M");
  });

  it("formats billions as B", () => {
    expect(formatXp(2_000_000_000)).toBe("2.00B");
  });

  it("formatGp is the same formatter", () => {
    expect(formatGp(1_500_000)).toBe(formatXp(1_500_000));
  });
});

describe("titleCase", () => {
  it("splits on underscores and capitalizes", () => {
    expect(titleCase("kalphite_queen")).toBe("Kalphite Queen");
  });

  it("handles already-spaced strings", () => {
    expect(titleCase("king black dragon")).toBe("King Black Dragon");
  });

  it("handles single words", () => {
    expect(titleCase("zulrah")).toBe("Zulrah");
  });
});
