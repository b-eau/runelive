// suggestProfileQueries heuristics (no LLM key in the test env, so the
// data-aware fallback path is exercised).

import { describe, expect, it } from "vitest";
import { db } from "@/lib/db";
import { createTestProfile } from "./fixtures";
import { suggestFollowups, suggestProfileQueries } from "@/lib/suggest";

describe("suggestProfileQueries", () => {
  it("personalizes from goals, near level-ups, and kill counts", async () => {
    const profileId = await createTestProfile();
    await db.skillState.createMany({
      data: [
        { profileId, skill: "attack", xp: 13_000_000n, level: 98 },
        { profileId, skill: "mining", xp: 100_000n, level: 40 },
      ],
    });
    await db.goal.create({ data: { profileId, title: "Quest cape", status: "ACTIVE" } });
    await db.killCountState.create({ data: { profileId, boss: "zulrah", kc: 250 } });

    const suggestions = await suggestProfileQueries(profileId);
    expect(suggestions).toHaveLength(4);
    const joined = suggestions.join(" ");
    expect(joined).toContain("Quest cape");
    expect(joined).toContain("Attack 99");
    expect(joined).toContain("250 Zulrah kills");
    for (const s of suggestions) expect(s.length).toBeLessThanOrEqual(140);
  });

  it("still returns four generic starters for an empty profile", async () => {
    const profileId = await createTestProfile();
    const suggestions = await suggestProfileQueries(profileId);
    expect(suggestions).toHaveLength(4);
  });

  it("skips quest suggestions when only miniquests remain", async () => {
    const profileId = await createTestProfile();
    await db.questState.createMany({
      data: [
        { profileId, quest: "Cook's Assistant", state: "FINISHED" },
        { profileId, quest: "Enter the Abyss", state: "NOT_STARTED" }, // miniquest
      ],
    });
    const suggestions = await suggestProfileQueries(profileId);
    expect(suggestions.join(" ")).not.toContain("Which quests should I knock out");
  });

  it("returns no followups without an LLM key", async () => {
    const profileId = await createTestProfile();
    const followups = await suggestFollowups(profileId, "hi", "hello there");
    expect(followups).toEqual([]);
  });

  it("caches per profile", async () => {
    const profileId = await createTestProfile();
    const first = await suggestProfileQueries(profileId);
    await db.goal.create({ data: { profileId, title: "Infernal cape", status: "ACTIVE" } });
    const second = await suggestProfileQueries(profileId);
    expect(second).toEqual(first); // served from cache despite new goal
  });

  it("biases heuristics by context and caches each context separately", async () => {
    const profileId = await createTestProfile();
    await db.skillState.create({ data: { profileId, skill: "slayer", xp: 100_000n, level: 40 } });

    const bank = await suggestProfileQueries(profileId, "bank");
    const bosses = await suggestProfileQueries(profileId, "bosses");
    expect(bank).toHaveLength(4);
    expect(bosses).toHaveLength(4);
    // Bank leads with money/gear framing; bosses leads with PvM framing.
    expect(bank.join(" ")).toMatch(/money-maker|gear upgrade|value/i);
    expect(bosses.join(" ")).toMatch(/boss|profitable/i);
    expect(bank).not.toEqual(bosses); // distinct cache entries
  });
});
