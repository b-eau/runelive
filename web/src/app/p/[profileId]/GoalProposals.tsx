"use client";

// Onboarding nudge: LLM-proposed, stats-grounded account goals shown when a
// profile has few goals of its own. One click adds a proposal to the profile.

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

type ProposedGoal = { title: string; rationale: string };

export default function GoalProposals({ profileId, show }: { profileId: string; show: boolean }) {
  const router = useRouter();
  const [goals, setGoals] = useState<ProposedGoal[]>([]);
  const [loading, setLoading] = useState(show);
  const [adding, setAdding] = useState<string | null>(null);

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

  async function add(goal: ProposedGoal) {
    setAdding(goal.title);
    try {
      const res = await fetch("/api/goals/add", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ profileId, title: goal.title, notes: goal.rationale }),
      });
      if (res.ok) {
        setGoals((gs) => gs.filter((g) => g.title !== goal.title));
        router.refresh(); // reflect the new goal in the active list
      }
    } finally {
      setAdding(null);
    }
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
                onClick={() => void add(g)}
                disabled={adding === g.title}
              >
                {adding === g.title ? "Adding…" : "+ Add"}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
