"use client";

// The landing-page hook: type your RSN, get the guest experience.

import { useRouter } from "next/navigation";
import { useState } from "react";

export default function TryForm() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [busy, setBusy] = useState(false);

  return (
    <form
      data-testid="try-form"
      onSubmit={(e) => {
        e.preventDefault();
        const name = username.trim();
        if (!name) return;
        setBusy(true);
        router.push(`/try/${encodeURIComponent(name)}`);
      }}
      style={{
        display: "flex",
        gap: 10,
        maxWidth: 440,
        margin: "0 auto",
        flexWrap: "wrap",
        justifyContent: "center",
      }}
    >
      <input
        type="text"
        placeholder="Your RuneScape username…"
        value={username}
        onChange={(e) => setUsername(e.target.value)}
        maxLength={12}
        aria-label="RuneScape username"
        style={{ flex: "1 1 220px", fontSize: 16, padding: "12px 16px" }}
      />
      <button className="btn primary" type="submit" disabled={busy || !username.trim()} style={{ fontSize: 16, padding: "12px 22px" }}>
        {busy ? "Looking up…" : "Try it free"}
      </button>
    </form>
  );
}
