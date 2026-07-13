import type { Goal } from "@prisma/client";
import { addGoal, deleteGoal } from "./actions";
import GoalProposals from "./GoalProposals";
import GoalRow from "./GoalRow";

export default function GoalsPanel({ profileId, goals }: { profileId: string; goals: Goal[] }) {
  const active = goals.filter((g) => g.status === "ACTIVE");
  const done = goals.filter((g) => g.status === "DONE");
  const addGoalForProfile = addGoal.bind(null, profileId);

  return (
    <div className="card">
      <h3>Account goals</h3>
      <p className="sub">Your mission steers Sidekick&apos;s advice — in chat, voice, and proactive tips.</p>
      <div style={{ display: "flex", flexDirection: "column", gap: 8, marginBottom: 14 }}>
        {active.length === 0 && <div className="empty">No goals yet. What&apos;s this account about?</div>}
        {active.map((g) => (
          <GoalRow key={g.id} profileId={profileId} goal={g} />
        ))}
        {done.map((g) => (
          <div
            key={g.id}
            style={{ display: "flex", alignItems: "center", gap: 10, padding: "4px 12px", color: "var(--ink-3)", fontSize: 13.5 }}
          >
            <span aria-hidden>🏆</span>
            <s style={{ flex: 1, minWidth: 0 }}>{g.title}</s>
            <form action={deleteGoal.bind(null, profileId, g.id)}>
              <button className="btn ghost" style={{ padding: "4px 8px", fontSize: 12 }} title="Delete goal" aria-label="Delete goal">
                🗑
              </button>
            </form>
          </div>
        ))}
      </div>
      <GoalProposals profileId={profileId} show={active.length < 2} />

      <form action={addGoalForProfile} style={{ display: "flex", gap: 8, marginTop: 14 }}>
        <input type="text" name="title" placeholder='e.g. "Quest cape, as AFK as possible"' required />
        <button className="btn" type="submit">
          Add
        </button>
      </form>
    </div>
  );
}
