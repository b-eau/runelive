import type { Goal } from "@prisma/client";
import { addGoal, setGoalStatus } from "./actions";

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
          <div
            key={g.id}
            style={{
              display: "flex",
              alignItems: "flex-start",
              gap: 10,
              background: "var(--surface-2)",
              border: "1px solid var(--border)",
              borderRadius: 10,
              padding: "10px 12px",
            }}
          >
            <span aria-hidden style={{ marginTop: 1 }}>
              🎯
            </span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 650, fontSize: 14 }}>{g.title}</div>
              {g.notes && (
                <div style={{ fontSize: 12.5, color: "var(--ink-3)", marginTop: 2 }}>{g.notes}</div>
              )}
            </div>
            <form action={setGoalStatus.bind(null, profileId, g.id, "DONE")}>
              <button className="btn ghost" style={{ padding: "4px 8px", fontSize: 12 }} title="Mark complete">
                ✓
              </button>
            </form>
            <form action={setGoalStatus.bind(null, profileId, g.id, "ARCHIVED")}>
              <button className="btn ghost" style={{ padding: "4px 8px", fontSize: 12 }} title="Archive">
                ✕
              </button>
            </form>
          </div>
        ))}
        {done.map((g) => (
          <div key={g.id} style={{ display: "flex", gap: 10, padding: "4px 12px", color: "var(--ink-3)", fontSize: 13.5 }}>
            <span aria-hidden>🏆</span>
            <s>{g.title}</s>
          </div>
        ))}
      </div>
      <form action={addGoalForProfile} style={{ display: "flex", gap: 8 }}>
        <input type="text" name="title" placeholder='e.g. "Quest cape, as AFK as possible"' required />
        <button className="btn" type="submit">
          Add
        </button>
      </form>
    </div>
  );
}
