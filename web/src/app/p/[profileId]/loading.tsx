import { ChartCardSkeleton, ListCardSkeleton, PageHeadSkeleton, Sk, StatRowSkeleton } from "@/components/Skeleton";

export default function OverviewLoading() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <PageHeadSkeleton title="Overview" sub="Loading your account…" />
      <StatRowSkeleton />
      <div className="card">
        <Sk h={15} w="30%" />
        <Sk h={13} w="60%" style={{ marginTop: 10 }} />
        <Sk h={30} w="85%" r={999} style={{ marginTop: 16 }} />
      </div>
      <div className="grid cols-2">
        <ChartCardSkeleton />
        <ChartCardSkeleton />
      </div>
      <div className="grid cols-2">
        <ListCardSkeleton rows={4} />
        <ListCardSkeleton rows={4} />
      </div>
    </div>
  );
}
