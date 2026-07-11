// linkByUsername: the username-only ("no plugin yet") link path. Uses
// GUEST_FIXTURES so lookups are deterministic and make no network calls.

import { beforeEach, describe, expect, it } from "vitest";
import { randomUUID } from "crypto";
import { db } from "@/lib/db";
import {
  AccountConflictError,
  isRsnLinked,
  linkByUsername,
  rsnAccountHash,
} from "@/lib/rsnLink";
import { PlayerNotFoundError } from "@/lib/lookup";

process.env.GUEST_FIXTURES = "1";

async function createTestUser(): Promise<string> {
  const user = await db.user.create({ data: { email: `rsn-${randomUUID()}@example.com` } });
  return user.id;
}

/** Unique per test so runs never collide on the unique accountHash. */
function freshUsername(): string {
  return `t${randomUUID().replace(/-/g, "").slice(0, 11)}`;
}

describe("rsnAccountHash / isRsnLinked", () => {
  it("namespaces and lowercases the username", () => {
    expect(rsnAccountHash("Lynx Titan")).toBe("rsn:lynx titan");
  });

  it("distinguishes placeholder accounts from plugin-linked ones", () => {
    expect(isRsnLinked("rsn:lynx titan")).toBe(true);
    expect(isRsnLinked("123456789")).toBe(false);
  });
});

describe("linkByUsername", () => {
  let userId: string;

  beforeEach(async () => {
    userId = await createTestUser();
  });

  it("rejects invalid usernames without touching the db", async () => {
    await expect(linkByUsername(userId, "not!!valid$$name")).rejects.toThrow(PlayerNotFoundError);
  });

  it("creates an account + STANDARD profile and materializes the snapshot", async () => {
    const username = freshUsername();
    const result = await linkByUsername(userId, username);
    expect(result.created).toBe(true);

    const account = await db.osrsAccount.findUnique({
      where: { accountHash: rsnAccountHash(username) },
      include: { profiles: true },
    });
    expect(account?.userId).toBe(userId);
    expect(account?.profiles).toHaveLength(1);
    expect(account?.profiles[0].kind).toBe("STANDARD");
    expect(account?.profiles[0].id).toBe(result.profileId);

    // Skills materialized, including the overall row and a same-day sample.
    const overall = await db.skillState.findUnique({
      where: { profileId_skill: { profileId: result.profileId, skill: "overall" } },
    });
    expect(overall).not.toBeNull();
    expect(overall!.level).toBeGreaterThan(0);
    const skillCount = await db.skillState.count({ where: { profileId: result.profileId } });
    expect(skillCount).toBeGreaterThanOrEqual(24); // 23 skills + overall
    const samples = await db.xpSample.count({ where: { profileId: result.profileId } });
    expect(samples).toBe(skillCount);

    // Boss KCs from the fixture (kraken 179 is the top entry).
    const kraken = await db.killCountState.findUnique({
      where: { profileId_boss: { profileId: result.profileId, boss: "kraken" } },
    });
    expect(kraken?.kc).toBe(179);

    // Combat level computed by the materializer; sync time stamped.
    const profile = await db.profile.findUnique({ where: { id: result.profileId } });
    expect(profile?.combatLevel).toBeGreaterThan(3);
    expect(profile?.lastSyncedAt).not.toBeNull();
  });

  it("re-linking the same username for the same user reuses the profile", async () => {
    const username = freshUsername();
    const first = await linkByUsername(userId, username);
    const second = await linkByUsername(userId, username);
    expect(second.created).toBe(false);
    expect(second.profileId).toBe(first.profileId);
    const accounts = await db.osrsAccount.count({
      where: { accountHash: rsnAccountHash(username) },
    });
    expect(accounts).toBe(1);
  });

  it("rejects a username already linked by a different user", async () => {
    const username = freshUsername();
    await linkByUsername(userId, username);
    const otherUser = await createTestUser();
    await expect(linkByUsername(otherUser, username)).rejects.toThrow(AccountConflictError);
  });

  it("a plugin-claim upgrade preserves the profile and its state", async () => {
    // Simulates what /api/link/claim does when it finds a placeholder.
    const username = freshUsername();
    const { profileId } = await linkByUsername(userId, username);
    const placeholder = await db.osrsAccount.findUnique({
      where: { accountHash: rsnAccountHash(username) },
    });
    await db.osrsAccount.update({
      where: { id: placeholder!.id },
      data: { accountHash: "999888777", displayName: username },
    });

    const upgraded = await db.osrsAccount.findUnique({
      where: { accountHash: "999888777" },
      include: { profiles: true },
    });
    expect(upgraded?.profiles[0].id).toBe(profileId);
    const skills = await db.skillState.count({ where: { profileId } });
    expect(skills).toBeGreaterThan(0);
  });
});
