import { NextResponse } from "next/server";
import { appUrl, clearSession } from "@/lib/auth";

export async function POST() {
  await clearSession();
  return NextResponse.redirect(`${appUrl()}/signin`, { status: 303 });
}
