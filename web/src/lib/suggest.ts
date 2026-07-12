// Personalized conversation starters for the authenticated Sidekick chat,
// derived from the profile's synced state. An LLM generates fresh ones when
// a key is configured (with heuristics as the fallback); results are cached
// in-memory per profile so opening the chat stays cheap.

import { db } from "./db";
import { runGeminiJson } from "./gemini";
import { anthropicEnabled, buildContext, llmEnabled } from "./sidekick";
import { formatXp, titleCase, xpForLevel } from "./osrs";
import { isMiniquest } from "./quests";
import Anthropic from "@anthropic-ai/sdk";

const SUGGESTION_TTL_MS = 6 * 60 * 60 * 1000;
const cache = new Map<string, { suggestions: string[]; expiresAt: number }>();

const SUGGESTION_SYSTEM = `You generate short starter questions an Old School RuneScape player might ask their AI account assistant. The assistant can see the player's synced skills, bank, quest log, diaries, boss kill counts, XP history, and stated goals.

Grounding rules — violating any of these makes a suggestion worthless:
- Every fact in a question must appear verbatim in the provided context. Never invent requirements, and never pair stats the context doesn't connect (e.g. don't cite two arbitrary skill levels in a diary question — if you can't tie a specific fact to the topic, ask the question without numbers).
- Never suggest content the context shows is already finished: if all quests are complete, no quest or quest-cape questions; if a diary tier is done, don't propose it.
- Miniquests are not quests: they award no quest points and never gate the quest cape.
- Each question must be answerable by the assistant from its data and tools.

Style: under 90 characters, first person, specific, and interesting enough to make them want the answer. Vary the topics: training, quests, bossing, bank/gear, diaries, goals, weekly progress.`;

const SUGGESTION_SCHEMA = {
  type: "object",
  properties: { suggestions: { type: "array", items: { type: "string" } } },
  required: ["suggestions"],
};

/** Data-aware fallback that needs no LLM. */
async function heuristicProfileSuggestions(profileId: string): Promise<string[]> {
  const [skills, goals, topKc, quests] = await Promise.all([
    db.skillState.findMany({ where: { profileId, skill: { not: "overall" } } }),
    db.goal.findMany({ where: { profileId, status: "ACTIVE" }, take: 2 }),
    db.killCountState.findFirst({ where: { profileId }, orderBy: { kc: "desc" } }),
    db.questState.findMany({ where: { profileId }, select: { quest: true, state: true } }),
  ]);

  const suggestions: string[] = [];
  for (const goal of goals) {
    suggestions.push(`What should I do this week toward "${goal.title}"?`);
  }

  const trainable = skills.filter((s) => s.level < 99);
  const closest = trainable
    .map((s) => ({ ...s, remaining: xpForLevel(s.level + 1) - Number(s.xp) }))
    .filter((s) => s.remaining > 0) // guard against stale level vs xp drift
    .sort((a, b) => a.remaining - b.remaining)[0];
  if (closest) {
    suggestions.push(
      `What's the fastest way to get ${titleCase(closest.skill)} ${closest.level + 1}? Only ${formatXp(closest.remaining)} xp to go.`,
    );
  }

  if (topKc) {
    suggestions.push(`After ${topKc.kc} ${titleCase(topKc.boss)} kills, what boss should I learn next?`);
  }
  // Only pitch questing when real quests (not miniquests) remain.
  const remainingQuests = quests.filter((q) => q.state !== "FINISHED" && !isMiniquest(q.quest));
  if (remainingQuests.length > 0) {
    suggestions.push("Which quests should I knock out next, given my stats?");
  }

  const fillers = [
    "How much XP did I gain this week, and in what?",
    "What money-makers fit my current stats?",
    "What's a realistic goal for me this month?",
    "What should I focus on in my next play session?",
  ];
  for (const filler of fillers) {
    if (suggestions.length >= 4) break;
    suggestions.push(filler);
  }
  return suggestions.slice(0, 4);
}

function sanitize(raw: string[] | undefined): string[] {
  return (raw ?? [])
    .filter((s) => typeof s === "string" && s.trim().length > 0)
    .map((s) => s.trim().slice(0, 140))
    .slice(0, 4);
}

const FOLLOWUP_SYSTEM = `You predict the next message an Old School RuneScape player is most likely to send their AI account assistant, given the assistant's last reply. Generate natural continuations: drilling into a recommendation, asking the obvious next step, or acting on what was suggested.

Rules: ground every followup in the reply or the player context (no invented facts), phrase in first person as the player, under 80 characters each, and make each one meaningfully different. Never repeat what was already answered.`;

/**
 * Predicts up to 3 likely user followups to an assistant reply, rendered as
 * one-click chips in the UI. Best-effort: returns [] without an LLM key or
 * on any failure.
 */
export async function suggestFollowups(
  profileId: string,
  userMessage: string,
  reply: string,
): Promise<string[]> {
  if (!llmEnabled()) return [];
  try {
    const prompt = `Player context:\n${await buildContext(profileId)}\n\nThe player asked:\n${userMessage.slice(0, 1000)}\n\nThe assistant replied:\n${reply.slice(0, 2500)}\n\nGenerate exactly 3 likely followup messages.`;
    if (anthropicEnabled()) {
      const client = new Anthropic();
      const response = await client.messages.create({
        model: "claude-haiku-4-5",
        max_tokens: 300,
        output_config: {
          format: { type: "json_schema", schema: { ...SUGGESTION_SCHEMA, additionalProperties: false } },
        },
        system: FOLLOWUP_SYSTEM,
        messages: [{ role: "user", content: prompt }],
      });
      const text = response.content.find((b) => b.type === "text");
      if (!text || text.type !== "text") return [];
      return sanitize((JSON.parse(text.text) as { suggestions?: string[] }).suggestions).slice(0, 3);
    }
    const parsed = await runGeminiJson<{ suggestions?: string[] }>({
      system: FOLLOWUP_SYSTEM,
      prompt,
      schema: SUGGESTION_SCHEMA,
    });
    return sanitize(parsed.suggestions).slice(0, 3);
  } catch (e) {
    console.warn("followup generation failed", e);
    return [];
  }
}

export async function suggestProfileQueries(profileId: string): Promise<string[]> {
  const hit = cache.get(profileId);
  if (hit && hit.expiresAt > Date.now()) return hit.suggestions;

  let suggestions = await heuristicProfileSuggestions(profileId);
  if (llmEnabled()) {
    try {
      const prompt = `Player context:\n${await buildContext(profileId)}\n\nGenerate exactly 4 starter questions.`;
      if (anthropicEnabled()) {
        const client = new Anthropic();
        const response = await client.messages.create({
          model: "claude-haiku-4-5",
          max_tokens: 400,
          output_config: {
            format: { type: "json_schema", schema: { ...SUGGESTION_SCHEMA, additionalProperties: false } },
          },
          system: SUGGESTION_SYSTEM,
          messages: [{ role: "user", content: prompt }],
        });
        const text = response.content.find((b) => b.type === "text");
        if (text && text.type === "text") {
          const fresh = sanitize((JSON.parse(text.text) as { suggestions?: string[] }).suggestions);
          if (fresh.length >= 2) suggestions = fresh;
        }
      } else {
        const parsed = await runGeminiJson<{ suggestions?: string[] }>({
          system: SUGGESTION_SYSTEM,
          prompt,
          schema: SUGGESTION_SCHEMA,
        });
        const fresh = sanitize(parsed.suggestions);
        if (fresh.length >= 2) suggestions = fresh;
      }
    } catch (e) {
      console.warn("profile suggestion generation failed, using heuristics", e);
    }
  }

  cache.set(profileId, { suggestions, expiresAt: Date.now() + SUGGESTION_TTL_MS });
  if (cache.size > 5000) {
    const oldest = cache.keys().next().value;
    if (oldest) cache.delete(oldest);
  }
  return suggestions;
}
