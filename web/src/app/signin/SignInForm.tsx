"use client";

import { useState } from "react";

const ERRORS: Record<string, string> = {
  expired: "That sign-in link expired or was already used. Request a new one.",
  missing: "That sign-in link was malformed. Request a new one.",
  oauth: "Google sign-in failed. Try again or use a magic link.",
  "google-disabled": "Google sign-in isn't configured on this server.",
};

export default function SignInForm({
  googleEnabled,
  next,
  initialError,
}: {
  googleEnabled: boolean;
  next?: string;
  initialError?: string;
}) {
  const [email, setEmail] = useState("");
  const [state, setState] = useState<"idle" | "sending" | "sent">("idle");
  const [error, setError] = useState<string | null>(initialError ? (ERRORS[initialError] ?? null) : null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setState("sending");
    const res = await fetch("/api/auth/magic", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email }),
    });
    if (res.ok) {
      setState("sent");
    } else {
      const body = await res.json().catch(() => ({}));
      setError(body.error ?? "Something went wrong");
      setState("idle");
    }
  }

  if (state === "sent") {
    return (
      <div style={{ textAlign: "center", padding: "12px 0" }}>
        <div style={{ fontSize: 34, marginBottom: 8 }}>📬</div>
        <div style={{ fontWeight: 650 }}>Check your inbox</div>
        <p className="sub" style={{ marginTop: 6 }}>
          We sent a sign-in link to <strong>{email}</strong>. It expires in 15 minutes.
        </p>
        <button className="btn ghost" onClick={() => setState("idle")}>
          Use a different email
        </button>
      </div>
    );
  }

  return (
    <form onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
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
        type="email"
        required
        placeholder="you@example.com"
        value={email}
        autoComplete="email"
        onChange={(e) => setEmail(e.target.value)}
      />
      <button className="btn primary" disabled={state === "sending"} type="submit">
        {state === "sending" ? "Sending…" : "Email me a magic link"}
      </button>
      {googleEnabled && (
        <>
          <div style={{ display: "flex", alignItems: "center", gap: 10, color: "var(--ink-3)", fontSize: 12 }}>
            <div style={{ flex: 1, height: 1, background: "var(--grid)" }} />
            or
            <div style={{ flex: 1, height: 1, background: "var(--grid)" }} />
          </div>
          <a className="btn" href={`/api/auth/google${next ? `?next=${encodeURIComponent(next)}` : ""}`}>
            <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden>
              <path
                fill="#4285F4"
                d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.4a5.5 5.5 0 0 1-2.4 3.6v3h3.9c2.3-2.1 3.6-5.2 3.6-8.8z"
              />
              <path
                fill="#34A853"
                d="M12 24c3.2 0 6-1.1 8-2.9l-3.9-3c-1.1.7-2.5 1.2-4.1 1.2-3.1 0-5.8-2.1-6.7-5H1.2v3.1A12 12 0 0 0 12 24z"
              />
              <path fill="#FBBC05" d="M5.3 14.3a7.2 7.2 0 0 1 0-4.6V6.6H1.2a12 12 0 0 0 0 10.8l4.1-3.1z" />
              <path
                fill="#EA4335"
                d="M12 4.8c1.8 0 3.3.6 4.6 1.8L20 3.2A12 12 0 0 0 1.2 6.6l4.1 3.1c.9-2.9 3.6-4.9 6.7-4.9z"
              />
            </svg>
            Continue with Google
          </a>
        </>
      )}
    </form>
  );
}
