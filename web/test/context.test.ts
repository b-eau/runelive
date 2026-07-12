// Quest/miniquest semantics in the agent's context (the "201 quests done,
// what do I need for the quest cape?" bug class).

import { describe, expect, it } from "vitest";
import { db } from "@/lib/db";
import { buildContext } from "@/lib/sidekick";
import { isMiniquest } from "@/lib/quests";
import { createTestProfile } from "./fixtures";

describe("isMiniquest", () => {
  it("classifies wiki and RuneLite spellings", () => {
    expect(isMiniquest("Enter the Abyss")).toBe(true);
    expect(isMiniquest("The Mage Arena")).toBe(true);
    expect(isMiniquest("Mage Arena I")).toBe(true);
    expect(isMiniquest("The Lair of Tarn Razorlorn")).toBe(true);
    expect(isMiniquest("Lair of Tarn Razorlor")).toBe(true);
  });

  it("does not flag real quests", () => {
    expect(isMiniquest("Dragon Slayer I")).toBe(false);
    expect(isMiniquest("Monkey Madness II")).toBe(false);
    expect(isMiniquest("The Fremennik Trials")).toBe(false);
  });
});

describe("buildContext quest semantics", () => {
  it("reports quest-cape eligibility when only miniquests remain", async () => {
    const profileId = await createTestProfile();
    await db.questState.createMany({
      data: [
        { profileId, quest: "Cook's Assistant", state: "FINISHED" },
        { profileId, quest: "Dragon Slayer I", state: "FINISHED" },
        { profileId, quest: "Enter the Abyss", state: "NOT_STARTED" },
        { profileId, quest: "The Mage Arena", state: "NOT_STARTED" },
      ],
    });

    const context = await buildContext(profileId);
    expect(context).toContain("ALL 2 quests complete");
    expect(context).toContain("Quest point cape is unlocked");
    expect(context).toContain("Miniquests");
    expect(context).toContain("Enter the Abyss");
  });

  it("counts miniquests separately when quests remain", async () => {
    const profileId = await createTestProfile();
    await db.questState.createMany({
      data: [
        { profileId, quest: "Cook's Assistant", state: "FINISHED" },
        { profileId, quest: "Dragon Slayer I", state: "NOT_STARTED" },
        { profileId, quest: "Enter the Abyss", state: "FINISHED" },
      ],
    });

    const context = await buildContext(profileId);
    expect(context).toContain("Quests: 1/2 complete (1 remaining)");
    expect(context).toContain("Miniquests (separate; no quest points): 1/1 complete");
  });

  it("summarizes completed diaries", async () => {
    const profileId = await createTestProfile();
    await db.diaryState.createMany({
      data: [
        { profileId, area: "Ardougne", tier: "EASY", completed: true },
        { profileId, area: "Ardougne", tier: "MEDIUM", completed: true },
        { profileId, area: "Varrock", tier: "EASY", completed: false },
      ],
    });

    const context = await buildContext(profileId);
    expect(context).toContain("Achievement diaries complete: Ardougne (Easy/Medium)");
  });
});
