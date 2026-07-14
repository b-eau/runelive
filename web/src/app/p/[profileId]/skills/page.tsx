import Link from "next/link";
import { notFound } from "next/navigation";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { SKILLS, formatXp, levelProgress, titleCase, xpForLevel } from "@/lib/osrs";
import { xpSeries } from "@/lib/series";
import TrendChart from "@/components/TrendChart";
import AskSidekick from "@/components/AskSidekick";
import { SKILL_EMOJI } from "@/components/skillEmoji";

export const metadata = { title: "Skills" };

export default async function SkillsPage({
  params,
  searchParams,
}: {
  params: Promise<{ profileId: string }>;
  searchParams: Promise<{ skill?: string; range?: string }>;
}) {
  const { profileId } = await params;
  const { skill: skillParam, range } = await searchParams;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  const states = await db.skillState.findMany({ where: { profileId } });
  const byName = new Map(states.map((s) => [s.skill, s]));
  const selected = skillParam && (SKILLS as readonly string[]).includes(skillParam) ? skillParam : "overall";
  const days = range === "90d" ? 90 : range === "30d" ? 30 : 365;
  const series = await xpSeries(profileId, selected, days);

  const selectedState = byName.get(selected);
  const gained = series.length >= 2 ? series[series.length - 1].value - series[0].value : 0;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <header className="page-head">
        <h1>Skills</h1>
        <p className="sub">Levels, XP, and progress over time — pick a skill to chart it</p>
      </header>
      <div className="card">
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
          <h3 style={{ fontSize: 17 }}>
            {SKILL_EMOJI[selected]} {titleCase(selected)}
          </h3>
          {selectedState && (
            <>
              <span className="pill gold">Level {selectedState.level}</span>
              <span className="pill">{formatXp(selectedState.xp)} xp</span>
              {selected !== "overall" && selectedState.level < 99 && (
                <span className="pill">
                  {formatXp(xpForLevel(selectedState.level + 1) - Number(selectedState.xp))} to {selectedState.level + 1}
                </span>
              )}
              {gained > 0 && <span className="pill green">+{formatXp(gained)} in range</span>}
            </>
          )}
          <div className="spacer" />
          <div style={{ display: "flex", gap: 4 }}>
            {[
              { key: "30d", label: "30d" },
              { key: "90d", label: "90d" },
              { key: "1y", label: "1y" },
            ].map((r) => (
              <Link
                key={r.key}
                href={`/p/${profileId}/skills?skill=${selected}&range=${r.key}`}
                className="btn ghost"
                style={{
                  padding: "4px 10px",
                  fontSize: 12.5,
                  background: (range ?? "1y") === r.key ? "var(--surface-3)" : undefined,
                }}
              >
                {r.label}
              </Link>
            ))}
          </div>
        </div>
        <TrendChart points={series} color="var(--series-1)" unit=" xp" height={240} />
        <AskSidekick profileId={profileId} context="skills" />
      </div>

      <div className="skill-grid">
        {["overall", ...SKILLS].map((skill) => {
          const s = byName.get(skill);
          const level = s?.level ?? 1;
          const xp = Number(s?.xp ?? 0);
          const maxed = skill === "overall" ? false : level >= 99;
          const progress = skill === "overall" ? 1 : levelProgress(xp);
          return (
            <Link
              key={skill}
              href={`/p/${profileId}/skills?skill=${skill}${range ? `&range=${range}` : ""}`}
              className={`skill-cell ${selected === skill ? "active" : ""}`}
              title={`${titleCase(skill)}: ${xp.toLocaleString()} xp`}
            >
              <span className="skill-emoji" aria-hidden>
                {SKILL_EMOJI[skill]}
              </span>
              <span className="skill-meta">
                <span className="skill-head">
                  <span className="skill-name">{titleCase(skill)}</span>
                  <span className="skill-level" style={maxed ? { color: "var(--gold)" } : undefined}>
                    {level.toLocaleString()}
                  </span>
                </span>
                <span className="skill-bar">
                  <span style={{ display: "block", width: `${Math.round(progress * 100)}%` }} className={maxed ? "maxed" : ""}>
                    <span style={{ display: "none" }} />
                  </span>
                </span>
              </span>
            </Link>
          );
        })}
      </div>
    </div>
  );
}
