"use client";

// The logged-out hook: look up a public player, render a rich stats view
// (level progress, closest level-ups, boss KCs), then pull the visitor into
// a limited Sidekick chat seeded with personalized starter queries.

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import AssistantMessage from "@/components/AssistantMessage";
import { SKILL_EMOJI } from "@/components/skillEmoji";

type GuestSkill = { skill: string; xp: number; level: number; rank?: number };
type Snapshot = {
  username: string;
  displayName: string;
  accountType: string | null;
  combatLevel: number | null;
  source: string;
  totalLevel: number;
  totalXp: number;
  skills: GuestSkill[];
  bosses: { name: string; kc: number }[];
  activities: { name: string; score: number }[];
};
type Msg = { role: "user" | "assistant"; content: string };

// OSRS xp curve, duplicated client-side (tiny) so progress renders without a
// round trip. Index = level; XP_TABLE[n] = xp required for level n.
const XP_TABLE: number[] = (() => {
  const t = [0, 0];
  let points = 0;
  for (let lvl = 1; lvl < 126; lvl++) {
    points += Math.floor(lvl + 300 * Math.pow(2, lvl / 7));
    t.push(Math.floor(points / 4));
  }
  return t;
})();

function fmt(n: number): string {
  if (n >= 1_000_000_000) return (n / 1_000_000_000).toFixed(2) + "B";
  if (n >= 10_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + "M";
  if (n >= 10_000) return (n / 1_000).toFixed(0) + "K";
  return n.toLocaleString("en-US");
}

function title(s: string): string {
  return s.split(/[_\s]+/).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}

function progressWithinLevel(s: GuestSkill): number {
  if (s.level >= 99) return 1;
  const cur = XP_TABLE[s.level];
  const next = XP_TABLE[s.level + 1];
  return Math.max(0, Math.min(1, (s.xp - cur) / (next - cur)));
}

const ACCOUNT_LABELS: Record<string, string> = {
  REGULAR: "Regular",
  IRONMAN: "Ironman",
  HARDCORE_IRONMAN: "Hardcore Ironman",
  ULTIMATE_IRONMAN: "Ultimate Ironman",
};

const SOURCE_LABELS: Record<string, string> = {
  wiseoldman: "via Wise Old Man",
  hiscores: "via official hiscores",
  fixture: "sample data",
};

/** Sign-up CTA target: after auth, land on /link with the username prefilled. */
function signupHref(username: string): string {
  return `/signin?next=${encodeURIComponent(`/link?username=${encodeURIComponent(username)}`)}`;
}

export default function GuestExperience({ username }: { username: string }) {
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState("");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [suggestions, setSuggestions] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    setState("loading");
    fetch("/api/guest/lookup", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username }),
    })
      .then(async (res) => {
        const body = await res.json();
        if (cancelled) return;
        if (!res.ok) {
          setError(body.error ?? "Lookup failed");
          setState("error");
          return;
        }
        setSnapshot(body.snapshot);
        setSuggestions(body.suggestions ?? []);
        setState("ready");
      })
      .catch(() => {
        if (!cancelled) {
          setError("The lookup service isn't responding. Try again in a moment.");
          setState("error");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [username]);

  if (state === "loading") {
    return (
      <div className="empty" style={{ paddingTop: 100 }} data-testid="guest-loading">
        <div style={{ fontSize: 36, marginBottom: 14 }}>🔎</div>
        <p style={{ fontSize: 16, color: "var(--ink-2)" }}>
          Looking up <strong>{username}</strong> on the hiscores…
        </p>
      </div>
    );
  }

  if (state === "error" || !snapshot) {
    return (
      <div className="empty" style={{ paddingTop: 100 }} data-testid="guest-error">
        <div style={{ fontSize: 36, marginBottom: 14 }}>🪦</div>
        <p style={{ fontSize: 16, color: "var(--ink-2)", maxWidth: 440, margin: "0 auto 20px" }}>{error}</p>
        <Link href="/" className="btn">
          Try a different name
        </Link>
      </div>
    );
  }

  return <GuestDashboard snapshot={snapshot} suggestions={suggestions} />;
}

function GuestDashboard({ snapshot, suggestions }: { snapshot: Snapshot; suggestions: string[] }) {
  const nearLevel = snapshot.skills
    .filter((s) => s.level < 99)
    .map((s) => ({ ...s, progress: progressWithinLevel(s), toNext: XP_TABLE[s.level + 1] - s.xp }))
    .sort((a, b) => b.progress - a.progress)
    .slice(0, 3);
  const near99 = snapshot.skills
    .filter((s) => s.level >= 90 && s.level < 99)
    .map((s) => ({ ...s, to99: XP_TABLE[99] - s.xp }))
    .sort((a, b) => a.to99 - b.to99)
    .slice(0, 3);
  const maxedCount = snapshot.skills.filter((s) => s.level >= 99).length;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16, paddingTop: 20 }} data-testid="guest-dashboard">
      <div style={{ display: "flex", alignItems: "baseline", gap: 12, flexWrap: "wrap" }}>
        <h1 style={{ fontSize: 28, margin: 0, letterSpacing: "-0.02em" }} data-testid="guest-name">
          {snapshot.displayName}
        </h1>
        {snapshot.accountType && (
          <span className="pill gold">{ACCOUNT_LABELS[snapshot.accountType] ?? snapshot.accountType}</span>
        )}
        {snapshot.combatLevel && <span className="pill">Combat {snapshot.combatLevel}</span>}
        <span style={{ fontSize: 12, color: "var(--ink-3)" }}>{SOURCE_LABELS[snapshot.source] ?? snapshot.source}</span>
      </div>

      <div className="grid cols-4">
        <div className="stat">
          <span className="label">Total level</span>
          <span className="value">{snapshot.totalLevel.toLocaleString()}</span>
          {maxedCount > 0 && <span className="delta">{maxedCount} skill{maxedCount > 1 ? "s" : ""} maxed 🏆</span>}
        </div>
        <div className="stat">
          <span className="label">Total XP</span>
          <span className="value">{fmt(snapshot.totalXp)}</span>
        </div>
        <div className="stat">
          <span className="label">Bosses fought</span>
          <span className="value">{snapshot.bosses.length}</span>
          {snapshot.bosses[0] && (
            <span className="delta">
              top: {title(snapshot.bosses[0].name)} ×{snapshot.bosses[0].kc.toLocaleString()}
            </span>
          )}
        </div>
        <div className="stat">
          <span className="label">Next level-up</span>
          {nearLevel[0] ? (
            <>
              <span className="value">
                {SKILL_EMOJI[nearLevel[0].skill]} {nearLevel[0].level + 1}
              </span>
              <span className="delta up">
                {title(nearLevel[0].skill)} — {fmt(nearLevel[0].toNext)} xp to go
              </span>
            </>
          ) : (
            <span className="value">Maxed!</span>
          )}
        </div>
      </div>

      <GuestChat snapshot={snapshot} suggestions={suggestions} />

      <div className="grid cols-2" style={{ alignItems: "start" }}>
        <div className="card">
          <h3>Almost there</h3>
          <p className="sub">Your closest level-ups{near99.length ? " and the road to 99" : ""}</p>
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            {nearLevel.map((s) => (
              <div key={s.skill}>
                <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13.5, marginBottom: 4 }}>
                  <span>
                    {SKILL_EMOJI[s.skill]} <strong>{title(s.skill)}</strong> {s.level} → {s.level + 1}
                  </span>
                  <span style={{ color: "var(--ink-3)" }}>{fmt(s.toNext)} xp to go</span>
                </div>
                <div className="skill-bar" style={{ height: 6 }}>
                  <span style={{ display: "block", width: `${Math.round(s.progress * 100)}%` }} />
                </div>
              </div>
            ))}
            {near99.map((s) => (
              <div key={`99-${s.skill}`}>
                <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13.5, marginBottom: 4 }}>
                  <span>
                    {SKILL_EMOJI[s.skill]} <strong>{title(s.skill)}</strong> {s.level} → <span style={{ color: "var(--gold)" }}>99</span>
                  </span>
                  <span style={{ color: "var(--ink-3)" }}>{fmt(s.to99)} xp to go</span>
                </div>
                <div className="skill-bar" style={{ height: 6 }}>
                  <span className="maxed" style={{ display: "block", width: `${Math.round((s.xp / XP_TABLE[99]) * 100)}%` }} />
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="card">
          <h3>Boss kill counts</h3>
          <p className="sub">From the public hiscores</p>
          {snapshot.bosses.length === 0 ? (
            <div className="empty">No ranked boss kills yet — ask Sidekick where to start!</div>
          ) : (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
              {snapshot.bosses.slice(0, 18).map((b) => (
                <span key={b.name} className="pill" style={{ fontSize: 12.5 }}>
                  {title(b.name)} <strong style={{ color: "var(--gold)" }}>×{b.kc.toLocaleString()}</strong>
                </span>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="card">
        <h3>All skills</h3>
        <p className="sub">Progress bars show how far into the current level you are</p>
        <div className="skill-grid" data-testid="guest-skills">
          {snapshot.skills.map((s) => {
            const maxed = s.level >= 99;
            return (
              <div key={s.skill} className="skill-cell" title={`${title(s.skill)}: ${s.xp.toLocaleString()} xp`}>
                <span className="skill-emoji" aria-hidden>
                  {SKILL_EMOJI[s.skill] ?? "❓"}
                </span>
                <span className="skill-meta">
                  <span className="skill-name">{title(s.skill)}</span>
                  <span className="skill-level" style={maxed ? { color: "var(--gold)" } : undefined}>
                    {s.level}
                  </span>
                  <span className="skill-bar">
                    <span
                      className={maxed ? "maxed" : ""}
                      style={{ display: "block", width: `${Math.round(progressWithinLevel(s) * 100)}%` }}
                    />
                  </span>
                </span>
              </div>
            );
          })}
        </div>
      </div>

      <div className="card" style={{ textAlign: "center", padding: 28 }}>
        <h3 style={{ fontSize: 17 }}>This is just your public hiscores.</h3>
        <p className="sub" style={{ maxWidth: 520, margin: "6px auto 16px" }}>
          Sign up to save this account to your dashboard in one click — then link the free RuneLite
          plugin for your bank, quest log, gear, and progress over time, plus goals-aware advice and
          a voice assistant.
        </p>
        <Link href={signupHref(snapshot.username)} className="btn primary" style={{ fontSize: 15, padding: "11px 24px" }}>
          Create your free account
        </Link>
      </div>
    </div>
  );
}

function GuestChat({ snapshot, suggestions }: { snapshot: Snapshot; suggestions: string[] }) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [limitReached, setLimitReached] = useState(false);
  const logRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, busy]);

  async function send(text: string) {
    const content = text.trim();
    if (!content || busy || limitReached) return;
    setInput("");
    setBusy(true);
    const nextMessages: Msg[] = [...messages, { role: "user", content }];
    setMessages(nextMessages);
    try {
      const res = await fetch("/api/guest/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: snapshot.username, messages: nextMessages }),
      });
      const body = await res.json();
      const reply: string = res.ok ? body.reply : (body.error ?? "Something went wrong.");
      setMessages((m) => [...m, { role: "assistant", content: reply }]);
      if (body.limitReached) setLimitReached(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", minHeight: 320, maxHeight: 480 }} data-testid="guest-chat">
      <div style={{ display: "flex", alignItems: "baseline", gap: 10, marginBottom: 8 }}>
        <h3>Ask Sidekick ✨</h3>
        <span style={{ fontSize: 12, color: "var(--ink-3)" }}>guest preview — knows your hiscores</span>
      </div>
      <div className="chat-log" ref={logRef}>
        {messages.length === 0 && (
          <div style={{ margin: "auto", textAlign: "center", padding: "8px 0" }}>
            <p style={{ fontSize: 13.5, color: "var(--ink-2)", marginBottom: 12 }}>
              Based on your stats, you could ask…
            </p>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8, justifyContent: "center" }} data-testid="guest-suggestions">
              {suggestions.map((s) => (
                <button
                  key={s}
                  className="btn ghost"
                  style={{ fontSize: 12.5, border: "1px solid var(--border)", maxWidth: 380, whiteSpace: "normal", textAlign: "left" }}
                  onClick={() => void send(s)}
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m, i) =>
          m.role === "assistant" ? (
            <AssistantMessage key={i} content={m.content} />
          ) : (
            <div key={i} className={`msg ${m.role}`}>
              {m.content}
            </div>
          ),
        )}
        {busy && <div className="msg assistant">Thinking…</div>}
        {limitReached && (
          <div style={{ textAlign: "center", padding: "8px 0" }}>
            <Link href={signupHref(snapshot.username)} className="btn primary">
              Sign up to keep chatting
            </Link>
          </div>
        )}
      </div>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void send(input);
        }}
        style={{ display: "flex", gap: 10, marginTop: 12 }}
      >
        <input
          type="text"
          placeholder={limitReached ? "Guest limit reached — sign up to continue" : "Ask about your stats, training, bosses…"}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          disabled={busy || limitReached}
          data-testid="guest-chat-input"
        />
        <button className="btn primary" type="submit" disabled={busy || limitReached || !input.trim()}>
          Send
        </button>
      </form>
    </div>
  );
}
