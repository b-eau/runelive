"use client";

import { useState } from "react";

export default function LinkClaim({ initialCode }: { initialCode: string }) {
  const [code, setCode] = useState(initialCode);
  const [state, setState] = useState<"idle" | "working" | "done">("idle");
  const [error, setError] = useState<string | null>(null);
  const [linkedName, setLinkedName] = useState("");

  async function claim(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setState("working");
    const res = await fetch("/api/link/claim", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
    const body = await res.json().catch(() => ({}));
    if (res.ok) {
      setLinkedName(body.displayName ?? "your account");
      setState("done");
    } else {
      setError(body.error ?? "Could not link the account");
      setState("idle");
    }
  }

  if (state === "done") {
    return (
      <div style={{ textAlign: "center", padding: "10px 0" }}>
        <div style={{ fontSize: 34, marginBottom: 8 }}>✅</div>
        <div style={{ fontWeight: 650 }}>{linkedName} is linked!</div>
        <p className="sub" style={{ marginTop: 6 }}>
          Head back to RuneLite — the plugin will pick this up within a few seconds and start syncing.
        </p>
        <a className="btn primary" href="/dashboard">
          Go to your dashboard
        </a>
      </div>
    );
  }

  return (
    <form onSubmit={claim} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      {error && (
        <div
          style={{
            background: "color-mix(in oklab, var(--critical) 14%, transparent)",
            border: "1px solid color-mix(in oklab, var(--critical) 40%, transparent)",
            borderRadius: 10,
            padding: "9px 12px",
            fontSize: 13,
          }}
        >
          {error}
        </div>
      )}
      <input
        type="text"
        placeholder="LINK CODE"
        value={code}
        onChange={(e) => setCode(e.target.value.toUpperCase())}
        style={{ textAlign: "center", letterSpacing: "0.3em", fontWeight: 700, fontSize: 18 }}
        maxLength={8}
        required
      />
      <button className="btn primary" type="submit" disabled={state === "working" || code.length < 8}>
        {state === "working" ? "Linking…" : "Link this account"}
      </button>
    </form>
  );
}
