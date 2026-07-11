import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { runSidekick } from "@/lib/sidekick";

export const maxDuration = 120; // LLM turns with tool use can take a while

const HISTORY_LIMIT = 24;
const TITLE_LIMIT = 60;

export async function POST(req: NextRequest) {
  const { profileId, conversationId, message } = (await req.json().catch(() => ({}))) as {
    profileId?: string;
    conversationId?: string;
    message?: string;
  };
  if (!profileId || !message?.trim()) {
    return NextResponse.json({ error: "profileId and message are required" }, { status: 400 });
  }
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const trimmed = message.trim().slice(0, 4000);

  // Resolve the thread: verify ownership when given, create on first message.
  let conversation = conversationId
    ? await db.conversation.findUnique({ where: { id: conversationId } })
    : null;
  if (conversationId && (!conversation || conversation.profileId !== profileId)) {
    return NextResponse.json({ error: "Conversation not found" }, { status: 404 });
  }
  conversation ??= await db.conversation.create({
    data: { profileId, title: trimmed.slice(0, TITLE_LIMIT) },
  });

  await db.chatMessage.create({
    data: { profileId, conversationId: conversation.id, role: "user", content: trimmed },
  });

  const recent = await db.chatMessage.findMany({
    where: { conversationId: conversation.id },
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
    await db.chatMessage.create({
      data: { profileId, conversationId: conversation.id, role: "assistant", content: reply },
    });
    await db.conversation.update({ where: { id: conversation.id }, data: { updatedAt: new Date() } });
    return NextResponse.json({ reply, conversationId: conversation.id, title: conversation.title });
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
  const conversationId = req.nextUrl.searchParams.get("conversationId");
  if (!profileId || !conversationId) {
    return NextResponse.json({ error: "profileId and conversationId required" }, { status: 400 });
  }
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });
  const conversation = await db.conversation.findUnique({ where: { id: conversationId } });
  if (!conversation || conversation.profileId !== profileId) {
    return NextResponse.json({ error: "Not found" }, { status: 404 });
  }

  const messages = await db.chatMessage.findMany({
    where: { conversationId },
    orderBy: { createdAt: "asc" },
    take: 200,
  });
  return NextResponse.json({
    messages: messages.map((m) => ({ id: m.id, role: m.role, content: m.content })),
  });
}
