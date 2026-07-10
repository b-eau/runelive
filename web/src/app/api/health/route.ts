import { NextResponse } from "next/server";
import { db } from "@/lib/db";

// Public liveness/readiness probe for uptime monitors. No auth, no
// user-identifying data — just proves the process is up and can reach its
// database.
export async function GET() {
  try {
    await db.$queryRaw`SELECT 1`;
    return NextResponse.json({ status: "ok", db: "ok" });
  } catch (e) {
    console.error("health check: db unreachable", e);
    return NextResponse.json({ status: "error", db: "unreachable" }, { status: 503 });
  }
}
