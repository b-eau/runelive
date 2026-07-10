import { notFound } from "next/navigation";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { formatGp, formatXp, titleCase } from "@/lib/osrs";
import { bankSeries, recentGains, xpSeries } from "@/lib/series";
import TrendChart from "@/components/TrendChart";
import GoalsPanel from "./GoalsPanel";

export const metadata = { title: "Overview" };

export default async function OverviewPage({ params }: { params: Promise<{ profileId: string }> }) {
  const { profileId } = await params;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  const [overall, skillCount, quests, bank, goals, overallSeries, bankPoints, gains] = await Promise.all([
    db.skillState.findUnique({ where: { profileId_skill: { profileId, skill: "overall" } } }),
    db.skillState.count({ where: { profileId, skill: { not: "overall" } } }),
    db.questState.groupBy({ by: ["state"], where: { profileId }, _count: true }),
    db.containerState.findUnique({ where: { profileId_container: { profileId, container: "BANK" } } }),
    db.goal.findMany({ where: { profileId }, orderBy: [{ status: "asc" }, { createdAt: "asc" }] }),
    xpSeries(profileId, "overall", 365),
    bankSeries(profileId, 365),
    recentGains(profileId, 7),
  ]);

  const questsDone = quests.find((q) => q.state === "FINISHED")?._count ?? 0;
  const questsTotal = quests.reduce((acc, q) => acc + q._count, 0);
  const weekGain = gains.reduce((acc, g) => acc + g.gained, 0);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <div className="grid cols-4">
        <div className="stat">
          <span className="label">Total level</span>
          <span className="value">{overall?.level.toLocaleString() ?? "—"}</span>
          <span className="delta">{skillCount} skills tracked</span>
        </div>
        <div className="stat">
          <span className="label">Total XP</span>
          <span className="value">{overall ? formatXp(overall.xp) : "—"}</span>
          {weekGain > 0 && <span className="delta up">+{formatXp(weekGain)} this week</span>}
        </div>
        <div className="stat">
          <span className="label">Quests</span>
          <span className="value">
            {questsDone}
            <span style={{ fontSize: 15, color: "var(--ink-3)", fontWeight: 600 }}> / {questsTotal}</span>
          </span>
          <span className="delta">{questsTotal - questsDone} remaining</span>
        </div>
        <div className="stat">
          <span className="label">Bank value</span>
          <span className="value">{bank ? formatGp(bank.value) : "—"}</span>
          <span className="delta">GE guide prices</span>
        </div>
      </div>

      <div className="grid cols-2">
        <div className="card">
          <h3>Total XP — last 12 months</h3>
          <p className="sub">Overall experience over time</p>
          <TrendChart points={overallSeries} color="var(--series-1)" unit=" xp" />
        </div>
        <div className="card">
          <h3>Bank value — last 12 months</h3>
          <p className="sub">Estimated from GE guide prices at each snapshot</p>
          <TrendChart points={bankPoints} color="var(--series-3)" unit=" gp" />
        </div>
      </div>

      <div className="grid cols-2">
        <GoalsPanel profileId={profileId} goals={goals} />
        <div className="card">
          <h3>This week&apos;s grind</h3>
          <p className="sub">XP gained per skill, trailing 7 days</p>
          {gains.length === 0 ? (
            <div className="empty">No XP gained this week (yet).</div>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Skill</th>
                  <th className="num">XP gained</th>
                </tr>
              </thead>
              <tbody>
                {gains.slice(0, 8).map((g) => (
                  <tr key={g.skill}>
                    <td>{titleCase(g.skill)}</td>
                    <td className="num" style={{ color: "var(--good)", fontWeight: 600 }}>
                      +{formatXp(g.gained)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
