import { db } from "./db";
import { currentUser } from "./auth";

/** Loads a profile and verifies it belongs to the signed-in user. */
export async function authorizedProfile(profileId: string) {
  const user = await currentUser();
  if (!user) return null;
  const profile = await db.profile.findUnique({
    where: { id: profileId },
    include: { account: true },
  });
  if (!profile || profile.account.userId !== user.id) return null;
  return profile;
}

/** All profiles for the signed-in user, grouped under their accounts. */
export async function userAccounts() {
  const user = await currentUser();
  if (!user) return [];
  return db.osrsAccount.findMany({
    where: { userId: user.id },
    include: { profiles: { orderBy: { createdAt: "asc" } } },
    orderBy: { createdAt: "asc" },
  });
}
