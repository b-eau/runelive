import { createHash, randomBytes } from "crypto";
import { cookies } from "next/headers";
import { cache } from "react";
import { db } from "./db";

const SESSION_COOKIE = "sidekick_session";
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days
const MAGIC_LINK_TTL_MS = 15 * 60 * 1000; // 15 minutes

export function appUrl(): string {
  return process.env.APP_URL ?? "http://localhost:3000";
}

export function newToken(bytes = 32): string {
  return randomBytes(bytes).toString("base64url");
}

/** Tokens are stored hashed so a DB leak doesn't leak live credentials. */
export function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

// ------------------------------------------------------------- sessions

export async function createSession(userId: string): Promise<string> {
  const token = newToken();
  await db.session.create({
    data: {
      id: hashToken(token),
      userId,
      expiresAt: new Date(Date.now() + SESSION_TTL_MS),
    },
  });
  return token;
}

export async function setSessionCookie(token: string) {
  const jar = await cookies();
  jar.set(SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: appUrl().startsWith("https"),
    maxAge: SESSION_TTL_MS / 1000,
    path: "/",
  });
}

export async function clearSession() {
  const jar = await cookies();
  const token = jar.get(SESSION_COOKIE)?.value;
  if (token) {
    await db.session.deleteMany({ where: { id: hashToken(token) } });
  }
  jar.delete(SESSION_COOKIE);
}

/** Returns the signed-in user or null. Cached per-request. */
export const currentUser = cache(async () => {
  const jar = await cookies();
  const token = jar.get(SESSION_COOKIE)?.value;
  if (!token) return null;
  const session = await db.session.findUnique({
    where: { id: hashToken(token) },
    include: { user: true },
  });
  if (!session || session.expiresAt < new Date()) return null;
  return session.user;
});

export async function requireUser() {
  const user = await currentUser();
  if (!user) throw new Error("UNAUTHENTICATED");
  return user;
}

// ----------------------------------------------------------- magic links

export async function createMagicLink(email: string): Promise<string> {
  const token = newToken();
  await db.magicLink.create({
    data: {
      token: hashToken(token),
      email: email.toLowerCase().trim(),
      expiresAt: new Date(Date.now() + MAGIC_LINK_TTL_MS),
    },
  });
  return `${appUrl()}/api/auth/callback?token=${token}`;
}

export async function sendMagicLink(email: string): Promise<void> {
  const url = await createMagicLink(email);
  const resendKey = process.env.RESEND_API_KEY;
  if (!resendKey) {
    // Dev transport: the link is printed to the server console.
    console.log(`\n━━━ OSRS Sidekick magic link for ${email} ━━━\n${url}\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n`);
    return;
  }
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${resendKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from: process.env.EMAIL_FROM ?? "OSRS Sidekick <onboarding@resend.dev>",
      to: [email],
      subject: "Sign in to OSRS Sidekick",
      html: `<p>Click to sign in to OSRS Sidekick:</p><p><a href="${url}">Sign in</a></p><p>This link expires in 15 minutes. If you didn't request it, ignore this email.</p>`,
    }),
  });
  if (!res.ok) {
    throw new Error(`Failed to send email: ${res.status} ${await res.text()}`);
  }
}

/** Consumes a magic link token; returns the user (created on first sign-in). */
export async function consumeMagicLink(token: string) {
  const hashed = hashToken(token);
  const link = await db.magicLink.findUnique({ where: { token: hashed } });
  if (!link || link.usedAt || link.expiresAt < new Date()) return null;
  await db.magicLink.update({ where: { token: hashed }, data: { usedAt: new Date() } });
  const user = await db.user.upsert({
    where: { email: link.email },
    update: {},
    create: { email: link.email },
  });
  return user;
}

// -------------------------------------------------------------- google

export function googleEnabled(): boolean {
  return !!(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET);
}

export function googleAuthUrl(state: string): string {
  const params = new URLSearchParams({
    client_id: process.env.GOOGLE_CLIENT_ID!,
    redirect_uri: `${appUrl()}/api/auth/google/callback`,
    response_type: "code",
    scope: "openid email profile",
    state,
    prompt: "select_account",
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${params}`;
}

export async function exchangeGoogleCode(code: string): Promise<{ email: string; name?: string } | null> {
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: process.env.GOOGLE_CLIENT_ID!,
      client_secret: process.env.GOOGLE_CLIENT_SECRET!,
      redirect_uri: `${appUrl()}/api/auth/google/callback`,
      grant_type: "authorization_code",
    }),
  });
  if (!res.ok) return null;
  const { id_token } = (await res.json()) as { id_token?: string };
  if (!id_token) return null;
  // The id_token comes straight from Google over TLS, so decoding the
  // payload without signature verification is safe here.
  const payload = JSON.parse(Buffer.from(id_token.split(".")[1], "base64url").toString());
  if (!payload.email) return null;
  return { email: payload.email as string, name: payload.name as string | undefined };
}
