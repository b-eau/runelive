"use client";

// Onboarding nudge: LLM-proposed, stats-grounded account goals shown when a
// profile has few goals of its own. One click hands the proposal to the
// parent's optimistic `onAdd`, so it appears in the goal list instantly.

import { useEffect, useState } from "react";

type ProposedGoal = { title: string; rationale: string };

export default function GoalProposals({
  profileId,
  show,
  onAdd,
}: {
  profileId: string;
  show: boolean;
  onAdd: (title: string, notes?: string) => void;
}) {
  const [goals, setGoals] = useState<ProposedGoal[]>([]);
  const [loading, setLoading] = useState(show);

  useEffect(() => {
    if (!show) return;
    let cancelled = false;
    fetch(`/api/goals/propose?profileId=${profileId}`)
      .then((r) => r.json())
      .then((d) => {
        if (!cancelled && Array.isArray(d.goals)) setGoals(d.goals);
      })
      .catch(() => {})
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [profileId, show]);

  function accept(goal: ProposedGoal) {
    setGoals((gs) => gs.filter((g) => g.title !== goal.title));
    onAdd(goal.title, goal.rationale);
  }

  if (!show || (!loading && goals.length === 0)) return null;

  return (
    <div className="goal-proposals">
      <div className="goal-proposals-head">
        <span aria-hidden>✨</span> Suggested goals for this account
      </div>
      {loading ? (
        <div className="sub" style={{ margin: 0 }}>
          <span className="thinking-dots">Reading your stats</span>
        </div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {goals.map((g) => (
            <div key={g.title} className="goal-proposal">
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 650, fontSize: 13.5 }}>{g.title}</div>
                {g.rationale && (
                  <div style={{ fontSize: 12, color: "var(--ink-3)", marginTop: 2 }}>{g.rationale}</div>
                )}
              </div>
              <button
                className="btn"
                style={{ padding: "5px 12px", fontSize: 12.5, flexShrink: 0 }}
                onClick={() => accept(g)}
              >
                + Add
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
