import { describe, expect, it } from "vitest";
import { bucketKey, buildSeries } from "@/lib/analytics";

describe("bucketKey", () => {
  it("day granularity returns the ISO date", () => {
    expect(bucketKey(new Date("2025-06-15T14:32:00Z"), "day")).toBe("2025-06-15");
  });

  it("month granularity floors to the 1st", () => {
    expect(bucketKey(new Date("2025-06-15T14:32:00Z"), "month")).toBe("2025-06-01");
    expect(bucketKey(new Date("2025-06-30T23:59:59Z"), "month")).toBe("2025-06-01");
  });

  it("week granularity floors to the preceding Monday", () => {
    // 2025-06-18 is a Wednesday; the Monday of that week is 2025-06-16.
    expect(bucketKey(new Date("2025-06-18T00:00:00Z"), "week")).toBe("2025-06-16");
    // A Monday floors to itself.
    expect(bucketKey(new Date("2025-06-16T00:00:00Z"), "week")).toBe("2025-06-16");
    // A Sunday belongs to the week that started the prior Monday.
    expect(bucketKey(new Date("2025-06-22T00:00:00Z"), "week")).toBe("2025-06-16");
  });
});

describe("buildSeries", () => {
  it("computes deltas against the previous bucket, first delta is 0", () => {
    const series = buildSeries(
      [
        { date: new Date("2025-01-01"), value: 100 },
        { date: new Date("2025-01-02"), value: 150 },
        { date: new Date("2025-01-03"), value: 400 },
      ],
      "day",
    );
    expect(series).toEqual([
      { date: "2025-01-01", value: 100, delta: 0 },
      { date: "2025-01-02", value: 150, delta: 50 },
      { date: "2025-01-03", value: 400, delta: 250 },
    ]);
  });

  it("last observation per bucket wins when multiple rows land in one bucket", () => {
    const series = buildSeries(
      [
        { date: new Date("2025-01-01T01:00:00Z"), value: 100 },
        { date: new Date("2025-01-01T20:00:00Z"), value: 300 },
      ],
      "day",
    );
    expect(series).toEqual([{ date: "2025-01-01", value: 300, delta: 0 }]);
  });

  it("sorts unordered input rows", () => {
    const series = buildSeries(
      [
        { date: new Date("2025-01-03"), value: 400 },
        { date: new Date("2025-01-01"), value: 100 },
        { date: new Date("2025-01-02"), value: 150 },
      ],
      "day",
    );
    expect(series.map((s) => s.date)).toEqual(["2025-01-01", "2025-01-02", "2025-01-03"]);
  });

  it("handles bigint values", () => {
    const series = buildSeries(
      [
        { date: new Date("2025-01-01"), value: 1_000_000n },
        { date: new Date("2025-01-02"), value: 1_500_000n },
      ],
      "day",
    );
    expect(series).toEqual([
      { date: "2025-01-01", value: 1_000_000, delta: 0 },
      { date: "2025-01-02", value: 1_500_000, delta: 500_000 },
    ]);
  });

  it("returns an empty series for no rows", () => {
    expect(buildSeries([], "day")).toEqual([]);
  });

  it("aggregates day rows into month buckets, last day in the month wins", () => {
    const series = buildSeries(
      [
        { date: new Date("2025-01-05"), value: 10 },
        { date: new Date("2025-01-25"), value: 40 },
        { date: new Date("2025-02-10"), value: 55 },
      ],
      "month",
    );
    expect(series).toEqual([
      { date: "2025-01-01", value: 40, delta: 0 },
      { date: "2025-02-01", value: 55, delta: 15 },
    ]);
  });
});
