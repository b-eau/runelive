import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { appUrl, createSession, exchangeGoogleCode, setSessionCookie } from "@/lib/auth";
import { db } from "@/lib/db";

export async function GET(req: NextRequest) {
  const code = req.nextUrl.searchParams.get("code");
  const state = req.nextUrl.searchParams.get("state");
  const jar = await cookies();
  const expected = jar.get("google_oauth_state")?.value;
  jar.delete("google_oauth_state");

  if (!code || !state || !expected || state !== expected) {
    return NextResponse.redirect(`${appUrl()}/signin?error=oauth`);
  }

  const identity = await exchangeGoogleCode(code);
  if (!identity) return NextResponse.redirect(`${appUrl()}/signin?error=oauth`);

  const user = await db.user.upsert({
    where: { email: identity.email.toLowerCase() },
    update: { name: identity.name ?? undefined },
    create: { email: identity.email.toLowerCase(), name: identity.name },
  });

  const session = await createSession(user.id);
  await setSessionCookie(session);

  const encodedNext = state.split(".")[1];
  let next = "/dashboard";
  try {
    const decoded = Buffer.from(encodedNext, "base64url").toString();
    if (decoded.startsWith("/") && !decoded.startsWith("//")) next = decoded;
  } catch {}
  return NextResponse.redirect(`${appUrl()}${next}`);
}
