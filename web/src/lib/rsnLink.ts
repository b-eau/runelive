// Link an OSRS account by username alone — the "no plugin yet" path that
// eases the guest -> signed-in transition. The public hiscores snapshot
// (same lookup the guest experience uses) is ingested as synthetic
// SKILLS / KILL_COUNT events, so skills, boss KCs, combat level, and daily
// rollups materialize exactly as if a plugin had synced them. Bank, quests,
// diaries, and live tracking stay empty until the RuneLite plugin links the
// same character, at which point the placeholder account is upgraded in
// place and its history carries over (see /api/link/claim).
//
// Placeholder accounts are keyed by accountHash "rsn:<username>" — a
// namespace that can never collide with RuneLite's numeric account hashes.

import { Prisma } from "@prisma/client";
import { db } from "./db";
import { lookupPlayer, normalizeUsername, PlayerNotFoundError, type GuestSnapshot } from "./lookup";
import { ingestEvents, type IngestEvent } from "./materialize";

export const RSN_HASH_PREFIX = "rsn:";

export function rsnAccountHash(username: string): string {
  return `${RSN_HASH_PREFIX}${username.toLowerCase()}`;
}

/** True for accounts linked by username only (no plugin yet). */
export function isRsnLinked(accountHash: string): boolean {
  return accountHash.startsWith(RSN_HASH_PREFIX);
}

export class AccountConflictError extends Error {
  constructor(displayName: string) {
    super(`"${displayName}" is already linked to a different Sidekick user.`);
    this.name = "AccountConflictError";
  }
}

function snapshotEvents(profileId: string, snapshot: GuestSnapshot): IngestEvent[] {
  const skills: Record<string, { xp: number; level: number }> = {
    overall: { xp: snapshot.totalXp, level: snapshot.totalLevel },
  };
  for (const s of snapshot.skills) skills[s.skill] = { xp: s.xp, level: s.level };

  // fetchedAt in the dedupe keys makes re-ingesting a cached snapshot a
  // no-op while letting a fresh lookup refresh the same profile.
  const events: IngestEvent[] = [
    {
      type: "SKILLS",
      occurredAt: snapshot.fetchedAt,
      dedupeKey: `rsn:${profileId}:skills:${snapshot.fetchedAt}`,
      payload: { skills },
    },
  ];
  for (const boss of snapshot.bosses) {
    events.push({
      type: "KILL_COUNT",
      occurredAt: snapshot.fetchedAt,
      dedupeKey: `rsn:${profileId}:kc:${boss.name}:${snapshot.fetchedAt}`,
      payload: { boss: boss.name, kc: boss.kc },
    });
  }
  return events;
}

/**
 * Links (or refreshes) a username-only account for the user.
 * Throws PlayerNotFoundError for invalid/unknown names and
 * AccountConflictError when another user already linked that username.
 */
export async function linkByUsername(
  userId: string,
  rawUsername: string,
): Promise<{ profileId: string; displayName: string; created: boolean }> {
  const username = normalizeUsername(rawUsername);
  if (!username) throw new PlayerNotFoundError(rawUsername);

  const snapshot = await lookupPlayer(username);
  const accountHash = rsnAccountHash(snapshot.username);

  const existing = await db.osrsAccount.findUnique({
    where: { accountHash },
    include: { profiles: true },
  });
  if (existing && existing.userId !== userId) throw new AccountConflictError(snapshot.displayName);

  let account = existing;
  if (!account) {
    try {
      account = await db.osrsAccount.create({
        data: { userId, accountHash, displayName: snapshot.displayName },
        include: { profiles: true },
      });
    } catch (e) {
      // Unique-hash race with a concurrent link of the same username.
      if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === "P2002") {
        throw new AccountConflictError(snapshot.displayName);
      }
      throw e;
    }
  } else if (account.displayName !== snapshot.displayName) {
    await db.osrsAccount.update({
      where: { id: account.id },
      data: { displayName: snapshot.displayName },
    });
  }

  const profile =
    account.profiles.find((p) => p.kind === "STANDARD") ??
    (await db.profile.create({ data: { accountId: account.id, kind: "STANDARD" } }));
  if (snapshot.accountType && profile.accountType !== snapshot.accountType) {
    await db.profile.update({
      where: { id: profile.id },
      data: { accountType: snapshot.accountType },
    });
  }

  await ingestEvents(profile.id, snapshotEvents(profile.id, snapshot));

  return { profileId: profile.id, displayName: snapshot.displayName, created: !existing };
}
