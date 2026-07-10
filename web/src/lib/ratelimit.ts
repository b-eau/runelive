// Minimal in-memory sliding-window rate limiter for unauthenticated guest
// endpoints. Per-process only — good enough to stop casual abuse and to be a
// polite citizen toward the upstream hiscores APIs. Swap for Upstash/Redis
// when running multiple serverless instances.

const windows = new Map<string, number[]>();

export function rateLimit(key: string, limit: number, windowMs: number): boolean {
  const now = Date.now();
  const hits = (windows.get(key) ?? []).filter((t) => now - t < windowMs);
  if (hits.length >= limit) {
    windows.set(key, hits);
    return false;
  }
  hits.push(now);
  windows.set(key, hits);
  if (windows.size > 10_000) {
    const oldest = windows.keys().next().value;
    if (oldest) windows.delete(oldest);
  }
  return true;
}

export function clientIp(req: Request): string {
  const forwarded = req.headers.get("x-forwarded-for");
  return forwarded?.split(",")[0]?.trim() || "unknown";
}
