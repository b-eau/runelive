import { randomUUID } from "crypto";
import { db } from "@/lib/db";

/** Creates a fresh User -> OsrsAccount -> Profile chain and returns the profile id. */
export async function createTestProfile(overrides?: { kind?: string; accountType?: string }): Promise<string> {
  const id = randomUUID();
  const user = await db.user.create({ data: { email: `test-${id}@example.com` } });
  const account = await db.osrsAccount.create({
    data: { userId: user.id, accountHash: `hash-${id}`, displayName: `tester-${id.slice(0, 8)}` },
  });
  const profile = await db.profile.create({
    data: {
      accountId: account.id,
      kind: overrides?.kind ?? "STANDARD",
      accountType: overrides?.accountType ?? "REGULAR",
    },
  });
  return profile.id;
}
