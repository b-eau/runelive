import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { appUrl, googleAuthUrl, googleEnabled, newToken } from "@/lib/auth";

export async function GET(req: NextRequest) {
  if (!googleEnabled()) {
    return NextResponse.redirect(`${appUrl()}/signin?error=google-disabled`);
  }
  const next = req.nextUrl.searchParams.get("next") ?? "/dashboard";
  const state = `${newToken(16)}.${Buffer.from(next).toString("base64url")}`;
  const jar = await cookies();
  jar.set("google_oauth_state", state, {
    httpOnly: true,
    sameSite: "lax",
    secure: appUrl().startsWith("https"),
    maxAge: 600,
    path: "/",
  });
  return NextResponse.redirect(googleAuthUrl(state));
}
