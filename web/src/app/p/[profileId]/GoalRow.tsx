"use client";

import type { Goal } from "@prisma/client";
import { useState } from "react";
import { setGoalStatus, updateGoal } from "./actions";

export default function GoalRow({ profileId, goal }: { profileId: string; goal: Goal }) {
  const [editing, setEditing] = useState(false);

  if (editing) {
    return (
      <form
        action={async (formData) => {
          await updateGoal(profileId, goal.id, formData);
          setEditing(false);
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
          <button
            type="button"
            className="btn ghost"
            style={{ padding: "4px 10px", fontSize: 12 }}
            onClick={() => setEditing(false)}
          >
            Cancel
          </button>
          <button className="btn primary" style={{ padding: "4px 12px", fontSize: 12 }} type="submit">
            Save
          </button>
        </div>
      </form>
    );
  }

  return (
    <div
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
        <div style={{ fontWeight: 650, fontSize: 14 }}>{goal.title}</div>
        {goal.notes && <div style={{ fontSize: 12.5, color: "var(--ink-3)", marginTop: 2 }}>{goal.notes}</div>}
      </div>
      <button
        type="button"
        className="btn ghost"
        style={{ padding: "4px 8px", fontSize: 12 }}
        title="Edit goal"
        aria-label="Edit goal"
        onClick={() => setEditing(true)}
      >
        ✎
      </button>
      <form action={setGoalStatus.bind(null, profileId, goal.id, "DONE")}>
        <button className="btn ghost" style={{ padding: "4px 8px", fontSize: 12 }} title="Mark complete" aria-label="Mark complete">
          ✓
        </button>
      </form>
      <form action={setGoalStatus.bind(null, profileId, goal.id, "ARCHIVED")}>
        <button className="btn ghost" style={{ padding: "4px 8px", fontSize: 12 }} title="Archive" aria-label="Archive">
          ✕
        </button>
      </form>
    </div>
  );
}
