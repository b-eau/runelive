// suggestProfileQueries heuristics (no LLM key in the test env, so the
// data-aware fallback path is exercised).

import { describe, expect, it } from "vitest";
import { db } from "@/lib/db";
import { createTestProfile } from "./fixtures";
import { suggestProfileQueries } from "@/lib/suggest";

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

  it("caches per profile", async () => {
    const profileId = await createTestProfile();
    const first = await suggestProfileQueries(profileId);
    await db.goal.create({ data: { profileId, title: "Infernal cape", status: "ACTIVE" } });
    const second = await suggestProfileQueries(profileId);
    expect(second).toEqual(first); // served from cache despite new goal
  });
});
