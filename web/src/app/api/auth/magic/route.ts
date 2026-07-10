import { NextRequest, NextResponse } from "next/server";
import { createMagicLink, sendMagicLink } from "@/lib/auth";

export async function POST(req: NextRequest) {
  const { email } = (await req.json().catch(() => ({}))) as { email?: string };
  if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
    return NextResponse.json({ error: "Enter a valid email address" }, { status: 400 });
  }

  // E2E seam: the Playwright suite can't read the server console, so when
  // E2E_AUTH_LINK=1 (never in production) the link is echoed in the response.
  if (process.env.E2E_AUTH_LINK === "1") {
    const devLink = await createMagicLink(email);
    return NextResponse.json({ ok: true, devLink });
  }

  try {
    await sendMagicLink(email);
  } catch (e) {
    console.error("magic link send failed", e);
    return NextResponse.json({ error: "Could not send the sign-in email" }, { status: 500 });
  }
  return NextResponse.json({ ok: true });
}
