import { notFound } from "next/navigation";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { titleCase } from "@/lib/osrs";
import { DIARY_AREAS, DIARY_TIERS } from "@/lib/quests";

export const metadata = { title: "Achievements" };

const CA_TIERS = ["easy", "medium", "hard", "elite", "master", "grandmaster"] as const;
const CLOG_SECTIONS = ["bosses", "raids", "clues", "minigames", "other"] as const;

function pct(obtained: number, total: number): number {
  return total > 0 ? Math.round((obtained / total) * 100) : 0;
}

export default async function AchievementsPage({ params }: { params: Promise<{ profileId: string }> }) {
  const { profileId } = await params;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  const [diaries, combatAch, clog, clogSlots] = await Promise.all([
    db.diaryState.findMany({ where: { profileId } }),
    db.combatAchievementState.findUnique({ where: { profileId } }),
    db.collectionLogState.findUnique({ where: { profileId } }),
    db.collectionLogSlot.count({ where: { profileId } }),
  ]);

  const diaryByArea = new Map<string, Map<string, boolean>>();
  for (const d of diaries) {
    if (!diaryByArea.has(d.area)) diaryByArea.set(d.area, new Map());
    diaryByArea.get(d.area)!.set(d.tier, d.completed);
  }
  const diaryDone = diaries.filter((d) => d.completed).length;

  const caThresholds = combatAch
    ? (JSON.parse(combatAch.thresholds) as Record<string, number>)
    : {};
  const gmThreshold = caThresholds.GRANDMASTER ?? 0;
  const clogSections = clog ? (JSON.parse(clog.sections) as Record<string, { obtained: number; total: number }>) : {};

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <div className="grid cols-2" style={{ alignItems: "start" }}>
        {/* Combat achievements */}
        <div className="card">
          <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
            <h3>Combat achievements</h3>
            {combatAch && <span className="pill gold">{combatAch.points.toLocaleString()} points</span>}
          </div>
          {!combatAch ? (
            <div className="empty">
              No combat achievement data yet — it syncs on login once the plugin is linked.
            </div>
          ) : (
            <>
              {gmThreshold > 0 && (
                <div className="meter" style={{ margin: "6px 0 14px" }}>
                  <span
                    className={combatAch.grandmaster ? "gold" : ""}
                    style={{ width: `${Math.min(100, pct(combatAch.points, gmThreshold))}%` }}
                  />
                </div>
              )}
              <div>
                {CA_TIERS.map((tier) => {
                  const complete = combatAch[tier];
                  const threshold = caThresholds[tier.toUpperCase()] ?? 0;
                  const toGo = threshold - combatAch.points;
                  return (
                    <div key={tier} className="ach-row">
                      <span className="ach-tier">{titleCase(tier)}</span>
                      <span style={{ flex: 1 }} className="ach-status">
                        {threshold > 0 ? `${threshold.toLocaleString()} pts` : ""}
                      </span>
                      <span
                        className="ach-status"
                        style={complete ? { color: "var(--good)", fontWeight: 700 } : undefined}
                      >
                        {complete
                          ? "✓ Complete"
                          : threshold > 0
                            ? `${toGo.toLocaleString()} to go`
                            : "—"}
                      </span>
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </div>

        {/* Collection log */}
        <div className="card">
          <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
            <h3>Collection log</h3>
            {clog && (
              <span className="pill gold">
                {clog.obtained.toLocaleString()} / {clog.total.toLocaleString()}
              </span>
            )}
          </div>
          {!clog ? (
            <div className="empty">
              No collection log data yet — open your collection log in-game to sync it.
            </div>
          ) : (
            <>
              <div className="meter" style={{ margin: "6px 0 14px" }}>
                <span style={{ width: `${pct(clog.obtained, clog.total)}%` }} />
              </div>
              {CLOG_SECTIONS.filter((s) => clogSections[s]).map((s) => {
                const { obtained, total } = clogSections[s];
                return (
                  <div key={s} style={{ marginBottom: 10 }}>
                    <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12.5, marginBottom: 3 }}>
                      <span>{titleCase(s)}</span>
                      <span style={{ color: "var(--ink-3)" }}>
                        {obtained}/{total}
                      </span>
                    </div>
                    <div className="meter">
                      <span style={{ width: `${pct(obtained, total)}%` }} />
                    </div>
                  </div>
                );
              })}
              <p className="sub" style={{ marginTop: 12, marginBottom: 0 }}>
                {clogSlots > 0
                  ? "Item-level slots are synced — ask the Sidekick whether you've obtained any specific drop."
                  : "Open your collection log's search in-game to sync individual item slots."}
              </p>
            </>
          )}
        </div>
      </div>

      {/* Achievement diaries */}
      <div className="card">
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
          <h3>Achievement diaries</h3>
          {diaries.length > 0 && <span className="pill">{diaryDone} tiers complete</span>}
        </div>
        {diaries.length === 0 ? (
          <div className="empty">
            No diary data yet — open the Achievement Diary tab in-game to sync it.
          </div>
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Area</th>
                  {DIARY_TIERS.map((t) => (
                    <th key={t} style={{ textAlign: "center" }}>
                      {titleCase(t.toLowerCase())}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {DIARY_AREAS.map((area) => (
                  <tr key={area}>
                    <td>{area}</td>
                    {DIARY_TIERS.map((tier) => {
                      const complete = diaryByArea.get(area)?.get(tier);
                      return (
                        <td key={tier} style={{ textAlign: "center" }}>
                          {complete === undefined ? (
                            <span style={{ color: "var(--ink-3)" }} title="Not synced">
                              –
                            </span>
                          ) : complete ? (
                            <span style={{ color: "var(--good)", fontWeight: 700 }} title="Complete">
                              ✓
                            </span>
                          ) : (
                            <span style={{ color: "var(--ink-3)" }} title="Incomplete">
                              ·
                            </span>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
