import { NextRequest, NextResponse } from "next/server";
import { sendMagicLink } from "@/lib/auth";

export async function POST(req: NextRequest) {
  const { email } = (await req.json().catch(() => ({}))) as { email?: string };
  if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
    return NextResponse.json({ error: "Enter a valid email address" }, { status: 400 });
  }
  try {
    await sendMagicLink(email);
  } catch (e) {
    console.error("magic link send failed", e);
    return NextResponse.json({ error: "Could not send the sign-in email" }, { status: 500 });
  }
  return NextResponse.json({ ok: true });
}
