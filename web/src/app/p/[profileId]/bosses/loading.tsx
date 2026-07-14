import { ChartCardSkeleton, ListCardSkeleton, PageHeadSkeleton } from "@/components/Skeleton";

export default function BossesLoading() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <PageHeadSkeleton title="Bosses" sub="Kill counts synced from in-game messages, charted over time" />
      <div className="grid cols-2" style={{ alignItems: "start" }}>
        <ListCardSkeleton rows={10} />
        <ChartCardSkeleton height={260} />
      </div>
    </div>
  );
}
