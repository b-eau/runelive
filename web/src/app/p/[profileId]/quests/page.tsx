import { notFound } from "next/navigation";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { DIARY_AREAS, DIARY_TIERS } from "@/lib/quests";

export const metadata = { title: "Quests" };

const STATE_META: Record<string, { color: string; label: string; order: number }> = {
  IN_PROGRESS: { color: "var(--warning)", label: "In progress", order: 0 },
  NOT_STARTED: { color: "var(--critical)", label: "Not started", order: 1 },
  FINISHED: { color: "var(--good)", label: "Complete", order: 2 },
};

export default async function QuestsPage({ params }: { params: Promise<{ profileId: string }> }) {
  const { profileId } = await params;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  const [quests, diaries] = await Promise.all([
    db.questState.findMany({ where: { profileId }, orderBy: { quest: "asc" } }),
    db.diaryState.findMany({ where: { profileId } }),
  ]);

  const done = quests.filter((q) => q.state === "FINISHED").length;
  const sorted = quests.sort(
    (a, b) => (STATE_META[a.state]?.order ?? 3) - (STATE_META[b.state]?.order ?? 3) || a.quest.localeCompare(b.quest),
  );
  const diaryByArea = new Map<string, Map<string, boolean>>();
  for (const d of diaries) {
    if (!diaryByArea.has(d.area)) diaryByArea.set(d.area, new Map());
    diaryByArea.get(d.area)!.set(d.tier, d.completed);
  }

  return (
    <div className="grid cols-2" style={{ alignItems: "start" }}>
      <div className="card">
        <h3>Quest log</h3>
        <p className="sub">
          {done} of {quests.length} complete · unfinished quests first
        </p>
        {quests.length === 0 ? (
          <div className="empty">No quest data yet — open your quest journal in-game to sync it.</div>
        ) : (
          <div style={{ maxHeight: 640, overflowY: "auto" }}>
            {sorted.map((q) => {
              const meta = STATE_META[q.state] ?? STATE_META.NOT_STARTED;
              return (
                <div key={q.quest} className="quest-row">
                  <span className="dot" style={{ background: meta.color }} aria-hidden />
                  <span style={{ flex: 1 }}>{q.quest}</span>
                  <span style={{ fontSize: 11.5, color: "var(--ink-3)", fontWeight: 600 }}>{meta.label}</span>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <div className="card">
        <h3>Achievement diaries</h3>
        <p className="sub">Completed tiers per area</p>
        {diaries.length === 0 ? (
          <div className="empty">No diary data yet — open the Achievement Diary tab in-game to sync it.</div>
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Area</th>
                  {DIARY_TIERS.map((t) => (
                    <th key={t} style={{ textAlign: "center" }}>
                      {t.charAt(0) + t.slice(1).toLowerCase()}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {DIARY_AREAS.filter((a) => diaryByArea.has(a)).map((area) => (
                  <tr key={area}>
                    <td>{area}</td>
                    {DIARY_TIERS.map((tier) => {
                      const complete = diaryByArea.get(area)?.get(tier);
                      return (
                        <td key={tier} style={{ textAlign: "center" }}>
                          {complete === undefined ? (
                            <span style={{ color: "var(--ink-3)" }}>–</span>
                          ) : complete ? (
                            <span style={{ color: "var(--good)", fontWeight: 700 }} title="Complete">✓</span>
                          ) : (
                            <span style={{ color: "var(--ink-3)" }} title="Incomplete">·</span>
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
