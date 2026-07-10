// Pure bucketing/series logic for the analytics endpoint, split out from the
// route handler so it's unit-testable without a request/response cycle.

export type SeriesRow = { date: Date; value: bigint | number };
export type SeriesPoint = { date: string; value: number; delta: number };

export type Granularity = "day" | "week" | "month";

/** Buckets a date to its UTC day/week(Monday)/month key, as an ISO date string. */
export function bucketKey(d: Date, granularity: string): string {
  if (granularity === "month") {
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}-01`;
  }
  if (granularity === "week") {
    const day = new Date(d);
    day.setUTCDate(day.getUTCDate() - ((day.getUTCDay() + 6) % 7)); // Monday
    return day.toISOString().slice(0, 10);
  }
  return d.toISOString().slice(0, 10);
}

/**
 * Buckets cumulative rows (last observation per bucket wins) and computes the
 * delta versus the previous bucket. Rows need not be pre-sorted.
 */
export function buildSeries(rows: SeriesRow[], granularity: string): SeriesPoint[] {
  const buckets = new Map<string, number>();
  for (const r of rows) buckets.set(bucketKey(r.date, granularity), Number(r.value));

  const series: SeriesPoint[] = [];
  let prev: number | null = null;
  for (const [date, value] of [...buckets.entries()].sort()) {
    series.push({ date, value, delta: prev === null ? 0 : value - prev });
    prev = value;
  }
  return series;
}
