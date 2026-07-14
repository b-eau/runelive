"use client";

// Account goals with optimistic mutations: add, edit-in-place, complete,
// archive, and delete all reflect in the UI instantly, then persist via
// server actions in the background. The local list is the source of truth
// after mount, so a mutation never waits on a revalidation round-trip —
// snappy for the player, and deterministic (no revalidation race).

import type { Goal } from "@prisma/client";
import { useCallback, useState } from "react";
import { addGoal, deleteGoal, setGoalStatus, updateGoal } from "./actions";
import GoalProposals from "./GoalProposals";
import GoalRecommendations from "./GoalRecommendations";

export default function GoalsPanel({ profileId, goals: initialGoals }: { profileId: string; goals: Goal[] }) {
  const [goals, setGoals] = useState<Goal[]>(initialGoals);
  const [editingId, setEditingId] = useState<string | null>(null);

  const active = goals.filter((g) => g.status === "ACTIVE");
  const done = goals.filter((g) => g.status === "DONE");

  // Apply an optimistic change, run the server action, and roll back on error.
  // `prev` is captured from the render closure (not inside the state updater,
  // which must stay pure and would double-fire the action in StrictMode).
  const mutate = useCallback(
    (next: Goal[], action: () => Promise<unknown>) => {
      const prev = goals;
      setGoals(next);
      action().catch(() => setGoals(prev));
    },
    [goals],
  );

  const complete = useCallback(
    (id: string) => {
      setEditingId((e) => (e === id ? null : e));
      mutate(
        goals.map((g) => (g.id === id ? { ...g, status: "DONE" } : g)),
        () => setGoalStatus(profileId, id, "DONE"),
      );
    },
    [goals, mutate, profileId],
  );

  const archive = useCallback(
    (id: string) => {
      mutate(
        goals.filter((g) => g.id !== id),
        () => setGoalStatus(profileId, id, "ARCHIVED"),
      );
    },
    [goals, mutate, profileId],
  );

  const remove = useCallback(
    (id: string) => {
      mutate(
        goals.filter((g) => g.id !== id),
        () => deleteGoal(profileId, id),
      );
    },
    [goals, mutate, profileId],
  );

  const saveEdit = useCallback(
    (id: string, title: string, notes: string) => {
      const t = title.trim();
      if (!t) return;
      const n = notes.trim() || null;
      setEditingId(null);
      const fd = new FormData();
      fd.set("title", t);
      fd.set("notes", n ?? "");
      mutate(
        goals.map((g) => (g.id === id ? { ...g, title: t, notes: n } : g)),
        () => updateGoal(profileId, id, fd),
      );
    },
    [goals, mutate, profileId],
  );

  const add = useCallback(
    (title: string, notes?: string) => {
      const t = title.trim();
      if (!t) return;
      const n = notes?.trim() || null;
      const optimistic: Goal = {
        id: `temp-${Date.now()}`,
        profileId,
        title: t.slice(0, 200),
        notes: n,
        status: "ACTIVE",
        createdAt: new Date(),
        updatedAt: new Date(),
      } as Goal;
      const fd = new FormData();
      fd.set("title", t);
      if (n) fd.set("notes", n);
      mutate([...goals, optimistic], () => addGoal(profileId, fd));
    },
    [goals, mutate, profileId],
  );

  return (
    <div className="card">
      <h3>Account goals</h3>
      <p className="sub">Your mission steers Sidekick&apos;s advice — in chat, voice, and proactive tips.</p>
      <div style={{ display: "flex", flexDirection: "column", gap: 8, marginBottom: 14 }}>
        {active.length === 0 && done.length === 0 && (
          <div className="empty">No goals yet. What&apos;s this account about?</div>
        )}
        {active.map((g) =>
          editingId === g.id ? (
            <GoalEditRow key={g.id} goal={g} onSave={saveEdit} onCancel={() => setEditingId(null)} />
          ) : (
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
                {g.notes && <div style={{ fontSize: 12.5, color: "var(--ink-3)", marginTop: 2 }}>{g.notes}</div>}
              </div>
              <button
                type="button"
                className="btn ghost"
                style={{ padding: "4px 8px", fontSize: 12 }}
                title="Edit goal"
                aria-label="Edit goal"
                onClick={() => setEditingId(g.id)}
              >
                ✎
              </button>
              <button
                type="button"
                className="btn ghost"
                style={{ padding: "4px 8px", fontSize: 12 }}
                title="Mark complete"
                aria-label="Mark complete"
                onClick={() => complete(g.id)}
              >
                ✓
              </button>
              <button
                type="button"
                className="btn ghost"
                style={{ padding: "4px 8px", fontSize: 12 }}
                title="Archive"
                aria-label="Archive"
                onClick={() => archive(g.id)}
              >
                ✕
              </button>
            </div>
          ),
        )}
        {done.map((g) => (
          <div
            key={g.id}
            style={{ display: "flex", alignItems: "center", gap: 10, padding: "4px 12px", color: "var(--ink-3)", fontSize: 13.5 }}
          >
            <span aria-hidden>🏆</span>
            <s style={{ flex: 1, minWidth: 0 }}>{g.title}</s>
            <button
              type="button"
              className="btn ghost"
              style={{ padding: "4px 8px", fontSize: 12 }}
              title="Delete goal"
              aria-label="Delete goal"
              onClick={() => remove(g.id)}
            >
              🗑
            </button>
          </div>
        ))}
      </div>
      <GoalProposals profileId={profileId} show={active.length < 2} onAdd={(title, notes) => add(title, notes)} />

      <GoalRecommendations
        profileId={profileId}
        existingTitles={active.map((g) => g.title)}
        onAdd={(title, notes) => add(title, notes)}
      />

      <form
        onSubmit={(e) => {
          e.preventDefault();
          const form = e.currentTarget;
          const value = (form.elements.namedItem("title") as HTMLInputElement).value;
          add(value);
          form.reset();
        }}
        style={{ display: "flex", gap: 8, marginTop: 14 }}
      >
        <input type="text" name="title" placeholder='e.g. "Quest cape, as AFK as possible"' required />
        <button className="btn" type="submit">
          Add
        </button>
      </form>
    </div>
  );
}

function GoalEditRow({
  goal,
  onSave,
  onCancel,
}: {
  goal: Goal;
  onSave: (id: string, title: string, notes: string) => void;
  onCancel: () => void;
}) {
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const form = e.currentTarget;
        const title = (form.elements.namedItem("title") as HTMLInputElement).value;
        const notes = (form.elements.namedItem("notes") as HTMLInputElement).value;
        onSave(goal.id, title, notes);
      }}
      style={{
        display: "flex",
        flexDirection: "column",
        gap: 6,
        background: "var(--surface-2)",
        border: "1px solid var(--gold)",
        borderRadius: 10,
        padding: "10px 12px",
      }}
    >
      <input name="title" defaultValue={goal.title} required autoFocus style={{ fontSize: 13.5 }} />
      <input name="notes" defaultValue={goal.notes ?? ""} placeholder="Notes (optional)" style={{ fontSize: 12.5 }} />
      <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
        <button type="button" className="btn ghost" style={{ padding: "4px 10px", fontSize: 12 }} onClick={onCancel}>
          Cancel
        </button>
        <button className="btn primary" style={{ padding: "4px 12px", fontSize: 12 }} type="submit">
          Save
        </button>
      </div>
    </form>
  );
}
