import Link from "next/link";
import { notFound } from "next/navigation";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { isRsnLinked } from "@/lib/rsnLink";
import { formatGp, formatXp, titleCase } from "@/lib/osrs";
import { bankSeries, recentGains, xpSeries } from "@/lib/series";
import { timeAgo } from "@/lib/timeAgo";
import TrendChart from "@/components/TrendChart";
import AskSidekick from "@/components/AskSidekick";
import GoalsPanel from "./GoalsPanel";

export const metadata = { title: "Overview" };

export default async function OverviewPage({ params }: { params: Promise<{ profileId: string }> }) {
  const { profileId } = await params;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  const [overall, skillCount, quests, bank, goals, overallSeries, bankPoints, gains, conversations] =
    await Promise.all([
      db.skillState.findUnique({ where: { profileId_skill: { profileId, skill: "overall" } } }),
      db.skillState.count({ where: { profileId, skill: { not: "overall" } } }),
      db.questState.groupBy({ by: ["state"], where: { profileId }, _count: true }),
      db.containerState.findUnique({ where: { profileId_container: { profileId, container: "BANK" } } }),
      db.goal.findMany({ where: { profileId }, orderBy: [{ status: "asc" }, { createdAt: "asc" }] }),
      xpSeries(profileId, "overall", 365),
      bankSeries(profileId, 365),
      recentGains(profileId, 7),
      db.conversation.findMany({
        where: { profileId },
        orderBy: { updatedAt: "desc" },
        take: 3,
        select: { id: true, title: true, updatedAt: true },
      }),
    ]);

  const questsDone = quests.find((q) => q.state === "FINISHED")?._count ?? 0;
  const questsTotal = quests.reduce((acc, q) => acc + q._count, 0);
  const weekGain = gains.reduce((acc, g) => acc + g.gained, 0);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <header className="page-head">
        <h1>Overview</h1>
        <p className="sub">Where {profile.account.displayName} stands, at a glance</p>
      </header>

      {isRsnLinked(profile.account.accountHash) && (
        <div
          style={{
            background: "color-mix(in oklab, var(--warning) 12%, transparent)",
            border: "1px solid color-mix(in oklab, var(--warning) 35%, transparent)",
            borderRadius: 10,
            padding: "8px 12px",
            fontSize: 12.5,
          }}
        >
          Linked by username — skills and boss KCs come from the public hiscores.{" "}
          <Link href="/link" style={{ fontWeight: 650 }}>
            Connect the RuneLite plugin
          </Link>{" "}
          to sync your bank, quests, gear, and live progress; everything here carries over.
        </div>
      )}

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

      {/* The critical path: resume a conversation or start a grounded one. */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
          <h3>
            <span aria-hidden>✨</span> Sidekick
          </h3>
          <p className="sub" style={{ margin: 0, flex: 1 }}>
            {conversations.length > 0 ? "Jump back in, or start something new" : "Your AI guide, grounded in this account"}
          </p>
          <Link href={`/p/${profileId}/chat`} className="btn" style={{ padding: "6px 12px", fontSize: 12.5 }}>
            Open chat →
          </Link>
        </div>
        {conversations.length > 0 && (
          <div style={{ display: "flex", flexDirection: "column", gap: 2, margin: "10px 0 2px" }}>
            {conversations.map((c) => (
              <Link key={c.id} href={`/p/${profileId}/chat?c=${c.id}`} className="conv-row">
                <span aria-hidden>💬</span>
                <span className="title">{c.title}</span>
                <span className="when">{timeAgo(c.updatedAt)}</span>
                <span className="go" aria-hidden>
                  →
                </span>
              </Link>
            ))}
          </div>
        )}
        <AskSidekick profileId={profileId} context="overview" />
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
