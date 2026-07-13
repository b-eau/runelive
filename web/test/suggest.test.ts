// suggestProfileQueries heuristics (no LLM key in the test env, so the
// data-aware fallback path is exercised).

import { describe, expect, it } from "vitest";
import { db } from "@/lib/db";
import { createTestProfile } from "./fixtures";
import {
  generateGoals,
  peekSuggestions,
  proposeGoals,
  refreshSuggestions,
  suggestFollowups,
  suggestProfileQueries,
} from "@/lib/suggest";

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

  it("peek never blocks: returns heuristics + needsRefresh when cache is cold", async () => {
    const profileId = await createTestProfile();
    await db.goal.create({ data: { profileId, title: "Max cape", status: "ACTIVE" } });

    const { suggestions, needsRefresh } = await peekSuggestions(profileId, "overview");
    expect(needsRefresh).toBe(true);
    expect(suggestions.length).toBeGreaterThanOrEqual(2);
    // Nothing was persisted by peek — it's read-only.
    expect(await db.suggestionCache.findUnique({ where: { profileId_context: { profileId, context: "overview" } } })).toBeNull();
  });

  it("refresh persists to the durable cache and peek then serves it without refresh", async () => {
    const profileId = await createTestProfile();
    await refreshSuggestions(profileId, "skills");

    const row = await db.suggestionCache.findUnique({
      where: { profileId_context: { profileId, context: "skills" } },
    });
    expect(row).not.toBeNull();

    const peek = await peekSuggestions(profileId, "skills");
    expect(peek.needsRefresh).toBe(false);
    expect(peek.suggestions).toEqual(JSON.parse(row!.payload));
  });

  it("serves a stale cache instantly while flagging it for refresh", async () => {
    const profileId = await createTestProfile();
    await refreshSuggestions(profileId, "bosses");
    // Age the cache past the 6h TTL.
    await db.suggestionCache.update({
      where: { profileId_context: { profileId, context: "bosses" } },
      data: { updatedAt: new Date(Date.now() - 7 * 60 * 60 * 1000) },
    });

    const peek = await peekSuggestions(profileId, "bosses");
    expect(peek.needsRefresh).toBe(true); // stale -> refresh
    expect(peek.suggestions.length).toBeGreaterThanOrEqual(2); // but still served
  });

  it("proposeGoals returns [] without an LLM key, and serves cached goals when present", async () => {
    const profileId = await createTestProfile();
    // No LLM key in the test env -> generation yields nothing.
    expect(await generateGoals("Player: tester, 92 Slayer.")).toEqual([]);
    expect(await proposeGoals(profileId)).toEqual([]);

    // A cached proposal (e.g. written on a prior LLM-enabled request) is served.
    await db.suggestionCache.create({
      data: {
        profileId,
        context: "goals",
        payload: JSON.stringify([{ title: "Reach 99 Slayer", rationale: "You're 92, the grind is nearly done." }]),
        updatedAt: new Date(),
      },
    });
    const goals = await proposeGoals(profileId);
    expect(goals).toHaveLength(1);
    expect(goals[0].title).toBe("Reach 99 Slayer");
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
