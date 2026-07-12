// Guest-mode LLM layer. Guests get a limited Sidekick: no DB, no tools —
// just their public hiscores snapshot as context, answered by a small,
// cheap model. Also generates the personalized "starter query" suggestions
// shown when their stats load.

import Anthropic from "@anthropic-ai/sdk";
import type { GuestSnapshot } from "./lookup";
import { runGeminiChat, runGeminiJson } from "./gemini";
import { anthropicEnabled, llmEnabled } from "./sidekick";
import { formatXp, titleCase, xpForLevel } from "./osrs";

// Deliberately the cheap tier: guest traffic is unauthenticated and the
// task (short suggestions, hiscores Q&A) doesn't need frontier reasoning.
export const GUEST_MODEL = "claude-haiku-4-5";

const MAX_HISTORY_MESSAGES = 12;
const MAX_MESSAGE_CHARS = 1_000;
export const GUEST_TURN_LIMIT = 10; // user messages per guest session

export type GuestMessage = { role: "user" | "assistant"; content: string };

/** Text summary of a public snapshot, shared by suggestions and chat. */
export function buildGuestContext(snapshot: GuestSnapshot): string {
  const sorted = [...snapshot.skills].sort((a, b) => b.level - a.level);
  const skillLines = sorted.map((s) => `${titleCase(s.skill)} ${s.level} (${formatXp(s.xp)} xp)`).join(", ");

  const nearLevel = snapshot.skills
    .filter((s) => s.level < 99)
    .map((s) => {
      const next = xpForLevel(s.level + 1);
      const cur = xpForLevel(s.level);
      return { ...s, remaining: next - s.xp, progress: (s.xp - cur) / (next - cur) };
    })
    .sort((a, b) => b.progress - a.progress)
    .slice(0, 3);

  return [
    `Player: ${snapshot.displayName}${snapshot.accountType ? ` (${snapshot.accountType.toLowerCase().replace(/_/g, " ")})` : ""}, combat level ${snapshot.combatLevel ?? "unknown"}.`,
    `Total level ${snapshot.totalLevel}, total XP ${formatXp(snapshot.totalXp)}.`,
    `Skills: ${skillLines}.`,
    nearLevel.length
      ? `Closest level-ups: ${nearLevel.map((s) => `${titleCase(s.skill)} ${s.level}→${s.level + 1} (${formatXp(s.remaining)} xp to go)`).join(", ")}.`
      : "",
    snapshot.bosses.length
      ? `Boss kill counts: ${snapshot.bosses.slice(0, 12).map((b) => `${titleCase(b.name)} ${b.kc}`).join(", ")}.`
      : "No boss kills on record.",
    snapshot.activities.length
      ? `Other: ${snapshot.activities.map((a) => `${a.name} ${a.score}`).join(", ")}.`
      : "",
    `Data source: ${snapshot.source === "wiseoldman" ? "Wise Old Man" : snapshot.source === "hiscores" ? "official OSRS hiscores" : "sample data"}.`,
  ]
    .filter(Boolean)
    .join("\n");
}

/** Rule-based suggestions — the no-API-key fallback, and the safety net. */
export function heuristicSuggestions(snapshot: GuestSnapshot): string[] {
  const suggestions: string[] = [];
  const trainable = snapshot.skills.filter((s) => s.level < 99);

  const closest = trainable
    .map((s) => ({ ...s, remaining: xpForLevel(s.level + 1) - s.xp }))
    .sort((a, b) => a.remaining - b.remaining)[0];
  if (closest) {
    suggestions.push(`What's the fastest way to level ${titleCase(closest.skill)} from ${closest.level} to ${closest.level + 1}?`);
  }

  const lowest = [...trainable].sort((a, b) => a.level - b.level)[0];
  if (lowest && lowest.skill !== closest?.skill) {
    suggestions.push(`My ${titleCase(lowest.skill)} is only ${lowest.level} — is it worth training, and how?`);
  }

  const highest = [...trainable].sort((a, b) => b.xp - a.xp)[0];
  if (highest) {
    suggestions.push(`How long would 99 ${titleCase(highest.skill)} take from ${formatXp(highest.xp)} xp?`);
  }

  if (snapshot.bosses.length > 0) {
    suggestions.push(`What boss should I try next after ${snapshot.bosses[0].kc} ${titleCase(snapshot.bosses[0].name)} kills?`);
  } else {
    suggestions.push("What's a good first boss for my stats?");
  }

  const fillers = [
    "What should I focus on this week to make the most progress?",
    "What money-makers fit my current stats?",
  ];
  for (const filler of fillers) {
    if (suggestions.length >= 4) break;
    suggestions.push(filler);
  }
  return suggestions.slice(0, 4);
}

/**
 * Personalized starter queries via the cheap model; falls back to
 * heuristics without an API key or on any model/parsing failure.
 */
const SUGGESTION_SYSTEM = `You generate short starter questions an Old School RuneScape player might ask an AI assistant about their own account.

Grounding rules: every fact in a question must appear verbatim in the provided context — never invent requirements or pair stats the context doesn't connect (if you can't tie a specific fact to the topic, ask without numbers). Never suggest content the context shows is already finished.

Style: under 90 characters, first person, specific to their actual stats, and interesting enough to make them want the answer. Vary the topics: training, bossing, milestones, efficiency.`;

function sanitizeSuggestions(raw: string[] | undefined): string[] {
  return (raw ?? [])
    .filter((s) => typeof s === "string" && s.trim().length > 0)
    .map((s) => s.trim().slice(0, 140))
    .slice(0, 4);
}

export async function suggestQueries(snapshot: GuestSnapshot): Promise<string[]> {
  if (!llmEnabled()) return heuristicSuggestions(snapshot);

  if (!anthropicEnabled()) {
    try {
      const parsed = await runGeminiJson<{ suggestions?: string[] }>({
        system: SUGGESTION_SYSTEM,
        prompt: `Player context:\n${buildGuestContext(snapshot)}\n\nGenerate exactly 4 starter questions.`,
        schema: {
          type: "object",
          properties: { suggestions: { type: "array", items: { type: "string" } } },
          required: ["suggestions"],
        },
      });
      const suggestions = sanitizeSuggestions(parsed.suggestions);
      return suggestions.length >= 2 ? suggestions : heuristicSuggestions(snapshot);
    } catch (e) {
      console.warn("guest suggestion generation failed, using heuristics", e);
      return heuristicSuggestions(snapshot);
    }
  }

  try {
    const client = new Anthropic();
    const response = await client.messages.create({
      model: GUEST_MODEL,
      max_tokens: 400,
      output_config: {
        format: {
          type: "json_schema",
          schema: {
            type: "object",
            properties: {
              suggestions: {
                type: "array",
                items: { type: "string" },
                description: "Exactly 4 short, personalized questions the player could ask",
              },
            },
            required: ["suggestions"],
            additionalProperties: false,
          },
        },
      },
      system: SUGGESTION_SYSTEM,
      messages: [
        {
          role: "user",
          content: `Player context:\n${buildGuestContext(snapshot)}\n\nGenerate exactly 4 starter questions.`,
        },
      ],
    });
    const text = response.content.find((b) => b.type === "text");
    if (!text || text.type !== "text") return heuristicSuggestions(snapshot);
    const parsed = JSON.parse(text.text) as { suggestions?: string[] };
    const suggestions = sanitizeSuggestions(parsed.suggestions);
    return suggestions.length >= 2 ? suggestions : heuristicSuggestions(snapshot);
  } catch (e) {
    console.warn("guest suggestion generation failed, using heuristics", e);
    return heuristicSuggestions(snapshot);
  }
}

function guestSystemPrompt(snapshot: GuestSnapshot): string {
  return `You are Sidekick, an expert Old School RuneScape companion, in GUEST mode on the OSRS Sidekick website. The visitor typed their username and you can see their public hiscores snapshot below — but nothing else (no bank, no quest log, no gear, no history).

Ground rules:
- Be concrete and specific to their actual stats: name methods, XP rates, requirements.
- If they ask about things you can't see (bank contents, quest progress, gear, XP gained over time), answer generally, then mention that linking the free RuneLite plugin unlocks that data. At most one such mention per reply — never be pushy.
- Respect the account type if known (ironmen can't trade or use the Grand Exchange).
- Keep replies tight: short paragraphs, plain lists, no tables or heavy markdown.

Player snapshot (public hiscores):
${buildGuestContext(snapshot)}`;
}

/** One guest chat turn. No tools, capped history, cheap model. */
export async function runGuestChat(snapshot: GuestSnapshot, history: GuestMessage[]): Promise<string> {
  // Sanitize the client-supplied history: cap counts/lengths, force
  // alternation, and make sure it starts with a user turn.
  const trimmed = history
    .filter((m) => (m.role === "user" || m.role === "assistant") && typeof m.content === "string")
    .slice(-MAX_HISTORY_MESSAGES)
    .map((m) => ({ role: m.role, content: m.content.slice(0, MAX_MESSAGE_CHARS) }));
  while (trimmed.length && trimmed[0].role !== "user") trimmed.shift();
  const messages: GuestMessage[] = [];
  for (const m of trimmed) {
    const prev = messages[messages.length - 1];
    if (prev && prev.role === m.role) prev.content += `\n${m.content}`;
    else messages.push({ ...m });
  }
  if (messages.length === 0) throw new Error("empty history");

  if (!llmEnabled()) {
    return [
      "⚠️ Demo mode — the server has no LLM API key (ANTHROPIC_API_KEY or GEMINI_API_KEY), so I can't reason about your question yet.",
      "",
      "Here's the snapshot I can see for you:",
      buildGuestContext(snapshot),
      "",
      "Sign up and link the RuneLite plugin to unlock the full Sidekick.",
    ].join("\n");
  }

  if (!anthropicEnabled()) {
    // No explicit maxTokens: the 4096 default leaves room for thinking.
    const text = await runGeminiChat({
      system: guestSystemPrompt(snapshot),
      history: messages,
    });
    return text || "Hmm, I came up empty — try rephrasing that.";
  }

  const client = new Anthropic();
  const response = await client.messages.create({
    model: GUEST_MODEL,
    max_tokens: 1024,
    system: guestSystemPrompt(snapshot),
    messages,
  });
  const text = response.content
    .filter((b) => b.type === "text")
    .map((b) => (b as { text: string }).text)
    .join("\n")
    .trim();
  return text || "Hmm, I came up empty — try rephrasing that.";
}
