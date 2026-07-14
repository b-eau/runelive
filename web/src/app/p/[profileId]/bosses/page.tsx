import Link from "next/link";
import { notFound } from "next/navigation";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { titleCase } from "@/lib/osrs";
import { kcSeries } from "@/lib/series";
import TrendChart from "@/components/TrendChart";
import AskSidekick from "@/components/AskSidekick";

export const metadata = { title: "Bosses" };

export default async function BossesPage({
  params,
  searchParams,
}: {
  params: Promise<{ profileId: string }>;
  searchParams: Promise<{ boss?: string }>;
}) {
  const { profileId } = await params;
  const { boss: bossParam } = await searchParams;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  const kcs = await db.killCountState.findMany({
    where: { profileId },
    orderBy: { kc: "desc" },
  });
  const selected = bossParam && kcs.some((k) => k.boss === bossParam) ? bossParam : kcs[0]?.boss;
  const series = selected ? await kcSeries(profileId, selected, 730) : [];
  const selectedKc = kcs.find((k) => k.boss === selected);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <header className="page-head">
        <h1>Bosses</h1>
        <p className="sub">Kill counts synced from in-game messages, charted over time</p>
      </header>
      <div className="grid cols-2" style={{ alignItems: "start" }}>
        <div className="card">
          <h3>Kill counts</h3>
        <p className="sub">Synced from in-game kill count messages</p>
        {kcs.length === 0 ? (
          <div className="empty">No boss kills tracked yet. Go bother a boss!</div>
        ) : (
          <div style={{ maxHeight: 620, overflowY: "auto" }}>
            <table className="table">
              <thead>
                <tr>
                  <th>Boss</th>
                  <th className="num">KC</th>
                </tr>
              </thead>
              <tbody>
                {kcs.map((k) => (
                  <tr key={k.boss} style={k.boss === selected ? { background: "var(--surface-2)" } : undefined}>
                    <td>
                      <Link href={`/p/${profileId}/bosses?boss=${encodeURIComponent(k.boss)}`} style={{ display: "block" }}>
                        {titleCase(k.boss)}
                      </Link>
                    </td>
                    <td className="num" style={{ fontWeight: 650 }}>
                      {k.kc.toLocaleString("en-US")}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <div className="card">
        {selected ? (
          <>
            <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
              <h3>{titleCase(selected)}</h3>
              {selectedKc && <span className="pill gold">{selectedKc.kc.toLocaleString("en-US")} kills</span>}
            </div>
            <p className="sub">Kill count over time (last 2 years)</p>
            <TrendChart points={series} color="var(--series-6)" height={260} />
          </>
        ) : (
          <div className="empty">Select a boss to see its trend.</div>
        )}
        <AskSidekick profileId={profileId} context="bosses" />
        </div>
      </div>
    </div>
  );
}
