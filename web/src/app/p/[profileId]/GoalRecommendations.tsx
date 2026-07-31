"use client";

// On-demand goal recommender: an "alternative flow" to typing a goal by hand.
// Available at any time (unlike the onboarding GoalProposals, which only shows
// for near-empty accounts), it asks the LLM for goals grounded in the account's
// current state AND its existing goals — so it fills gaps rather than echoing
// what the player already tracks. Regenerate pulls a fresh set on demand.

import { useCallback, useState } from "react";

type ProposedGoal = { title: string; rationale: string };

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

export default function GoalRecommendations({
  profileId,
  existingTitles,
  onAdd,
}: {
  profileId: string;
  existingTitles: string[];
  onAdd: (title: string, notes?: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [goals, setGoals] = useState<ProposedGoal[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(false);

  const load = useCallback(
    async (refresh: boolean) => {
      setLoading(true);
      setError(false);
      try {
        const url = `/api/goals/recommend?profileId=${profileId}${refresh ? "&refresh=1" : ""}`;
        const res = await fetch(url);
        const data = await res.json();
        setGoals(Array.isArray(data.goals) ? data.goals : []);
      } catch {
        setError(true);
      } finally {
        setLoading(false);
        setLoaded(true);
      }
    },
    [profileId],
  );

  const toggle = useCallback(() => {
    setOpen((o) => {
      const next = !o;
      if (next && !loaded) void load(false);
      return next;
    });
  }, [loaded, load]);

  const accept = useCallback(
    (goal: ProposedGoal) => {
      setGoals((gs) => gs.filter((g) => g.title !== goal.title));
      onAdd(goal.title, goal.rationale);
    },
    [onAdd],
  );

  // Never surface a recommendation for a goal the player already has (covers
  // optimistic adds the server-side cache hasn't caught up with yet).
  const existing = new Set(existingTitles.map(norm));
  const visible = goals.filter((g) => !existing.has(norm(g.title)));

  return (
    <div style={{ marginTop: 14 }}>
      <button
        type="button"
        className="btn ghost"
        style={{ width: "100%", justifyContent: "center", fontSize: 13 }}
        aria-expanded={open}
        onClick={toggle}
      >
        ✨ Recommend goals for me
      </button>

      {open && (
        <div className="goal-proposals" style={{ marginTop: 8 }}>
          <div className="goal-proposals-head" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <span>
              <span aria-hidden>✨</span> Recommended for this account
            </span>
            {loaded && !loading && (
              <button
                type="button"
                className="btn ghost"
                style={{ padding: "2px 8px", fontSize: 11.5 }}
                onClick={() => void load(true)}
                title="Generate a fresh set"
              >
                ↻ Regenerate
              </button>
            )}
          </div>

          {loading ? (
            <div className="sub" style={{ margin: 0 }}>
              <span className="thinking-dots">Reading your account</span>
            </div>
          ) : error ? (
            <div className="sub" style={{ margin: 0 }}>
              Couldn&apos;t reach the recommender.{" "}
              <button type="button" className="btn ghost" style={{ padding: "2px 8px", fontSize: 12 }} onClick={() => void load(true)}>
                Retry
              </button>
            </div>
          ) : visible.length === 0 ? (
            <div className="sub" style={{ margin: 0 }}>
              No new ideas right now — you&apos;ve got solid goals already. Try Regenerate for more.
            </div>
          ) : (
            <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
              {visible.map((g) => (
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
      )}
    </div>
  );
}
