"use client";

// Contextual "Ask Sidekick" prompts, embedded inside the card they relate to
// (the skill chart, the bank table, …) rather than a generic banner. Each
// chip deep-links into the chat with the prompt pre-sent (ChatPanel's ?ask=).
//
// Layout-stable by design: the row reserves its height and shows ghost chips
// while loading, and resolved suggestions are cached per-session so returning
// to a tab paints them instantly.

import Link from "next/link";
import { useEffect, useState } from "react";

export type AskContext = "overview" | "skills" | "quests" | "bank" | "bosses" | "achievements";

const CONTEXT_LABELS: Record<AskContext, string> = {
  overview: "Ask Sidekick",
  skills: "Training ideas",
  quests: "Quest help",
  bank: "About your bank",
  bosses: "PvM ideas",
  achievements: "Completionist help",
};

export default function AskSidekick({
  profileId,
  context,
  bare = false,
}: {
  profileId: string;
  context: AskContext;
  /** Skip the divider treatment when the row isn't appended to card content. */
  bare?: boolean;
}) {
  // null = loading (ghost chips hold the space); [] = nothing to show.
  const [suggestions, setSuggestions] = useState<string[] | null>(null);

  useEffect(() => {
    const key = `sk-sugg:${profileId}:${context}`;
    try {
      const cached = sessionStorage.getItem(key);
      if (cached) setSuggestions(JSON.parse(cached) as string[]);
    } catch {}
    let cancelled = false;
    fetch(`/api/chat/suggestions?profileId=${profileId}&context=${context}`)
      .then((r) => r.json())
      .then((d) => {
        if (cancelled || !Array.isArray(d.suggestions)) return;
        setSuggestions(d.suggestions);
        try {
          sessionStorage.setItem(key, JSON.stringify(d.suggestions));
        } catch {}
      })
      .catch(() => {
        if (!cancelled) setSuggestions((s) => s ?? []);
      });
    return () => {
      cancelled = true;
    };
  }, [profileId, context]);

  if (suggestions !== null && suggestions.length === 0) return null;

  return (
    <div className={`ask ${bare ? "bare" : ""}`}>
      <span className="ask-label">
        <span aria-hidden>✨</span> {CONTEXT_LABELS[context]}
      </span>
      <div className="ask-chips">
        {suggestions === null
          ? [168, 132, 196, 150].map((w, i) => (
              <span key={i} className="ask-chip ghost-chip sk" style={{ width: w }} aria-hidden />
            ))
          : suggestions.map((s) => (
              <Link key={s} href={`/p/${profileId}/chat?ask=${encodeURIComponent(s)}`} className="ask-chip">
                {s}
              </Link>
            ))}
      </div>
    </div>
  );
}
