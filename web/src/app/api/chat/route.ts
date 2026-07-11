import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { runSidekick } from "@/lib/sidekick";

export const maxDuration = 120; // LLM turns with tool use can take a while

const HISTORY_LIMIT = 24;

export async function POST(req: NextRequest) {
  const { profileId, message } = (await req.json().catch(() => ({}))) as {
    profileId?: string;
    message?: string;
  };
  if (!profileId || !message?.trim()) {
    return NextResponse.json({ error: "profileId and message are required" }, { status: 400 });
  }
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const trimmed = message.trim().slice(0, 4000);
  await db.chatMessage.create({ data: { profileId, role: "user", content: trimmed } });

  const recent = await db.chatMessage.findMany({
    where: { profileId },
    orderBy: { createdAt: "desc" },
    take: HISTORY_LIMIT,
  });
  // Rebuild oldest-first and coalesce any accidental same-role runs.
  const history: { role: "user" | "assistant"; content: string }[] = [];
  for (const m of recent.reverse()) {
    const role = m.role === "assistant" ? "assistant" : "user";
    const prev = history[history.length - 1];
    if (prev && prev.role === role) prev.content += `\n${m.content}`;
    else history.push({ role, content: m.content });
  }
  if (history.length === 0 || history[0].role !== "user") {
    history.unshift({ role: "user", content: trimmed });
  }

  const startedAt = Date.now();
  try {
    const reply = await runSidekick(profileId, history);
    console.log(`sidekick turn completed in ${Date.now() - startedAt}ms`);
    await db.chatMessage.create({ data: { profileId, role: "assistant", content: reply } });
    return NextResponse.json({ reply });
  } catch (e) {
    console.error(`sidekick error after ${Date.now() - startedAt}ms`, e);
    return NextResponse.json(
      { error: "Sidekick hit a snag answering that. Try again in a moment." },
      { status: 500 },
    );
  }
}

export async function GET(req: NextRequest) {
  const profileId = req.nextUrl.searchParams.get("profileId");
  if (!profileId) return NextResponse.json({ error: "profileId required" }, { status: 400 });
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });
  const messages = await db.chatMessage.findMany({
    where: { profileId },
    orderBy: { createdAt: "asc" },
    take: 200,
  });
  return NextResponse.json({
    messages: messages.map((m) => ({ id: m.id, role: m.role, content: m.content })),
  });
}
