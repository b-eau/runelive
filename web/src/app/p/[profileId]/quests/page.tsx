import { notFound } from "next/navigation";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { isMiniquest } from "@/lib/quests";
import SuggestionBar from "@/components/SuggestionBar";

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

  const quests = await db.questState.findMany({ where: { profileId }, orderBy: { quest: "asc" } });

  // Miniquests award no quest points; count and label them separately.
  const fullQuests = quests.filter((q) => !isMiniquest(q.quest));
  const done = fullQuests.filter((q) => q.state === "FINISHED").length;
  const sorted = quests.sort(
    (a, b) => (STATE_META[a.state]?.order ?? 3) - (STATE_META[b.state]?.order ?? 3) || a.quest.localeCompare(b.quest),
  );

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <SuggestionBar profileId={profileId} context="quests" />
      <div className="card">
        <h3>Quest log</h3>
        <p className="sub">
          {done} of {fullQuests.length} quests complete · unfinished first · diaries live on the
          Achievements tab
        </p>
        {quests.length === 0 ? (
          <div className="empty">No quest data yet — open your quest journal in-game to sync it.</div>
        ) : (
          <div style={{ maxHeight: 680, overflowY: "auto" }}>
            {sorted.map((q) => {
              const meta = STATE_META[q.state] ?? STATE_META.NOT_STARTED;
              const mini = isMiniquest(q.quest);
              return (
                <div key={q.quest} className="quest-row">
                  <span className="dot" style={{ background: meta.color }} aria-hidden />
                  <span style={{ flex: 1 }}>
                    {q.quest}
                    {mini && (
                      <span style={{ fontSize: 10.5, color: "var(--ink-3)", marginLeft: 6 }}>miniquest</span>
                    )}
                  </span>
                  <span style={{ fontSize: 11.5, color: "var(--ink-3)", fontWeight: 600 }}>{meta.label}</span>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
