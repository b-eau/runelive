import { NextRequest, NextResponse } from "next/server";
import { appUrl, consumeMagicLink, createSession, setSessionCookie } from "@/lib/auth";

export async function GET(req: NextRequest) {
  const token = req.nextUrl.searchParams.get("token");
  const next = req.nextUrl.searchParams.get("next") ?? "/dashboard";
  if (!token) return NextResponse.redirect(`${appUrl()}/signin?error=missing`);

  const user = await consumeMagicLink(token);
  if (!user) return NextResponse.redirect(`${appUrl()}/signin?error=expired`);

  const session = await createSession(user.id);
  await setSessionCookie(session);
  // Only allow same-site relative redirects.
  const safeNext = next.startsWith("/") && !next.startsWith("//") ? next : "/dashboard";
  return NextResponse.redirect(`${appUrl()}${safeNext}`);
}
