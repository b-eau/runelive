// Skeleton primitives for route-level loading states. Every loading.tsx
// mirrors its page's real layout with these blocks so navigation paints
// instantly and the content settles in place without layout shift.

export function Sk({
  h = 14,
  w = "100%",
  r,
  style,
}: {
  h?: number;
  w?: number | string;
  r?: number;
  style?: React.CSSProperties;
}) {
  return (
    <span
      className="sk"
      style={{ display: "block", height: h, width: w, borderRadius: r, ...style }}
      aria-hidden
    />
  );
}

/** A row of stat tiles matching .grid.cols-4 of .stat blocks. */
export function StatRowSkeleton() {
  return (
    <div className="grid cols-4">
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="stat">
          <Sk h={12} w="55%" />
          <Sk h={28} w="70%" style={{ marginTop: 6 }} />
          <Sk h={12} w="45%" style={{ marginTop: 6 }} />
        </div>
      ))}
    </div>
  );
}

/** A card containing a chart-sized block, matching TrendChart cards. */
export function ChartCardSkeleton({ height = 220 }: { height?: number }) {
  return (
    <div className="card">
      <Sk h={15} w="40%" />
      <Sk h={12} w="55%" style={{ marginTop: 8, marginBottom: 14 }} />
      <Sk h={height} r={10} />
    </div>
  );
}

/** A card of table-ish rows. */
export function ListCardSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div className="card">
      <Sk h={15} w="35%" />
      <Sk h={12} w="50%" style={{ marginTop: 8, marginBottom: 16 }} />
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {Array.from({ length: rows }, (_, i) => (
          <Sk key={i} h={18} w={`${100 - ((i * 13) % 25)}%`} />
        ))}
      </div>
    </div>
  );
}

/** Page heading placeholder matching .page-head. */
export function PageHeadSkeleton({ title, sub }: { title: string; sub?: string }) {
  // Route titles are static, so render the real text immediately — only the
  // data below it shimmers.
  return (
    <header className="page-head">
      <h1>{title}</h1>
      {sub && <p className="sub">{sub}</p>}
    </header>
  );
}
