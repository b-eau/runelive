import { ListCardSkeleton, PageHeadSkeleton, Sk } from "@/components/Skeleton";

export default function AchievementsLoading() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <PageHeadSkeleton title="Achievements" sub="Combat achievements, collection log, and diaries" />
      <div className="grid cols-2" style={{ alignItems: "start" }}>
        <ListCardSkeleton rows={6} />
        <ListCardSkeleton rows={6} />
      </div>
      <div className="card">
        <Sk h={15} w="30%" />
        <Sk h={280} r={10} style={{ marginTop: 14 }} />
      </div>
    </div>
  );
}
