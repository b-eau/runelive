"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

export default function LinkByName({ initialUsername }: { initialUsername: string }) {
  const router = useRouter();
  const [username, setUsername] = useState(initialUsername);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function link(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setWorking(true);
    const res = await fetch("/api/link/username", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username }),
    });
    const body = await res.json().catch(() => ({}));
    if (res.ok && body.profileId) {
      router.push(`/p/${body.profileId}`);
    } else {
      setError(body.error ?? "Could not link that username");
      setWorking(false);
    }
  }

  return (
    <form onSubmit={link} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
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
        placeholder="Your OSRS username"
        value={username}
        onChange={(e) => setUsername(e.target.value)}
        maxLength={12}
        required
      />
      <button className="btn primary" type="submit" disabled={working || username.trim().length === 0}>
        {working ? "Fetching hiscores…" : "Link by username"}
      </button>
    </form>
  );
}
