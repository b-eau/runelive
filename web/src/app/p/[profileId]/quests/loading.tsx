import { PageHeadSkeleton, Sk } from "@/components/Skeleton";

export default function QuestsLoading() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <PageHeadSkeleton title="Quests" sub="Loading your quest log…" />
      <div className="card">
        <Sk h={15} w="25%" />
        <Sk h={12} w="40%" style={{ marginTop: 8, marginBottom: 16 }} />
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          {Array.from({ length: 12 }, (_, i) => (
            <div key={i} style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <Sk h={8} w={8} r={4} />
              <Sk h={15} w={`${45 + ((i * 17) % 40)}%`} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
