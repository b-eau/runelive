// Personalized conversation starters for the authenticated Sidekick chat,
// derived from the profile's synced state. An LLM generates fresh ones when
// a key is configured (with heuristics as the fallback); results are cached
// in-memory per profile so opening the chat stays cheap.

import { db } from "./db";
import { runGeminiJson } from "./gemini";
import { anthropicEnabled, buildContext, llmEnabled } from "./sidekick";
import { formatXp, titleCase, xpForLevel } from "./osrs";
import Anthropic from "@anthropic-ai/sdk";

const SUGGESTION_TTL_MS = 6 * 60 * 60 * 1000;
const cache = new Map<string, { suggestions: string[]; expiresAt: number }>();

const SUGGESTION_SYSTEM =
  "You generate short starter questions an Old School RuneScape player might ask their AI account assistant. The assistant can see the player's synced skills, bank, quest log, boss kill counts, XP history, and stated goals. Each question must be specific to the player's actual data (reference real skills/levels/bosses/goals from the context), under 90 characters, phrased in first person, and interesting enough to make them want the answer. Vary the topics: training, quests, bossing, bank/gear, goals, weekly progress.";

const SUGGESTION_SCHEMA = {
  type: "object",
  properties: { suggestions: { type: "array", items: { type: "string" } } },
  required: ["suggestions"],
};

/** Data-aware fallback that needs no LLM. */
async function heuristicProfileSuggestions(profileId: string): Promise<string[]> {
  const [skills, goals, topKc, questCount] = await Promise.all([
    db.skillState.findMany({ where: { profileId, skill: { not: "overall" } } }),
    db.goal.findMany({ where: { profileId, status: "ACTIVE" }, take: 2 }),
    db.killCountState.findFirst({ where: { profileId }, orderBy: { kc: "desc" } }),
    db.questState.count({ where: { profileId } }),
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
  if (questCount > 0) {
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
