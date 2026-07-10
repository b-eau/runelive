"use server";

import { revalidatePath } from "next/cache";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";

export async function addGoal(profileId: string, formData: FormData) {
  const profile = await authorizedProfile(profileId);
  if (!profile) throw new Error("Not found");
  const title = String(formData.get("title") ?? "").trim();
  if (!title) return;
  const notes = String(formData.get("notes") ?? "").trim() || null;
  await db.goal.create({ data: { profileId, title: title.slice(0, 200), notes } });
  revalidatePath(`/p/${profileId}`);
}

export async function setGoalStatus(profileId: string, goalId: string, status: "ACTIVE" | "DONE" | "ARCHIVED") {
  const profile = await authorizedProfile(profileId);
  if (!profile) throw new Error("Not found");
  await db.goal.updateMany({ where: { id: goalId, profileId }, data: { status } });
  revalidatePath(`/p/${profileId}`);
}
