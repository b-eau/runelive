import { PageHeadSkeleton, Sk } from "@/components/Skeleton";

export default function SkillsLoading() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <PageHeadSkeleton title="Skills" sub="Levels, XP, and progress over time — pick a skill to chart it" />
      <div className="card">
        <Sk h={20} w="45%" />
        <Sk h={240} r={10} style={{ marginTop: 16 }} />
      </div>
      <div className="skill-grid">
        {Array.from({ length: 24 }, (_, i) => (
          <div key={i} className="skill-cell" style={{ pointerEvents: "none" }}>
            <span className="skill-emoji sk" />
            <span className="skill-meta">
              <Sk h={12} w="70%" />
              <Sk h={3} w="100%" style={{ marginTop: 8 }} />
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
