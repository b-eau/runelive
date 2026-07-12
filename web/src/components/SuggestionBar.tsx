"use client";

// Proactive "Ask Sidekick" chips shown on each profile tab. Each chip
// deep-links into the chat with the prompt pre-sent (ChatPanel's ?ask=).
// Suggestions are fetched client-side so the tab renders instantly.

import Link from "next/link";
import { useEffect, useState } from "react";

export default function SuggestionBar({
  profileId,
  context,
}: {
  profileId: string;
  context: "overview" | "skills" | "quests" | "bank" | "bosses";
}) {
  const [suggestions, setSuggestions] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    fetch(`/api/chat/suggestions?profileId=${profileId}&context=${context}`)
      .then((r) => r.json())
      .then((d) => {
        if (!cancelled && Array.isArray(d.suggestions)) setSuggestions(d.suggestions);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [profileId, context]);

  if (suggestions.length === 0) return null;

  return (
    <div className="suggest-bar">
      <span className="suggest-bar-label">✨ Ask Sidekick</span>
      <div className="suggest-bar-chips">
        {suggestions.map((s) => (
          <Link
            key={s}
            href={`/p/${profileId}/chat?ask=${encodeURIComponent(s)}`}
            className="suggest-chip"
          >
            {s}
          </Link>
        ))}
      </div>
    </div>
  );
}
