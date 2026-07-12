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
// Don't let concurrent tab loads all fire the LLM before the first finishes.
const REFRESH_MIN_INTERVAL_MS = 60 * 1000;

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

// Where the suggestions appear. "chat" is the general starter set; the others
// bias both the heuristics and the LLM toward that tab's subject matter.
export type SuggestContext = "chat" | "overview" | "skills" | "quests" | "bank" | "bosses";

export const SUGGEST_CONTEXTS: SuggestContext[] = ["chat", "overview", "skills", "quests", "bank", "bosses"];

const CONTEXT_FOCUS: Record<SuggestContext, string> = {
  chat: "",
  overview: "Focus on the player's headline progress: goals, weekly gains, and their most impactful next milestone.",
  skills: "Focus every question on skill training: the fastest routes to their next levels, efficient methods, and XP goals.",
  quests: "Focus on quests the player has NOT finished, quest rewards worth chasing, and achievement diary tiers still open. If all quests are done, do not mention quests or the quest cape.",
  bank: "Focus on the player's wealth: money-making methods that fit their stats, gear upgrades they could afford, and what to buy next.",
  bosses: "Focus on PvM: which boss to learn next given their kill counts, gear upgrades for bosses they already do, and kill-count milestones.",
};

/** Shared LLM call for all suggestion flavors. Returns [] on any failure. */
async function llmSuggestions(system: string, prompt: string, max: number): Promise<string[]> {
  if (!llmEnabled()) return [];
  try {
    if (anthropicEnabled()) {
      const client = new Anthropic();
      const response = await client.messages.create({
        model: "claude-haiku-4-5",
        max_tokens: 400,
        output_config: {
          format: { type: "json_schema", schema: { ...SUGGESTION_SCHEMA, additionalProperties: false } },
        },
        system,
        messages: [{ role: "user", content: prompt }],
      });
      const text = response.content.find((b) => b.type === "text");
      if (!text || text.type !== "text") return [];
      return sanitize((JSON.parse(text.text) as { suggestions?: string[] }).suggestions).slice(0, max);
    }
    const parsed = await runGeminiJson<{ suggestions?: string[] }>({ system, prompt, schema: SUGGESTION_SCHEMA });
    return sanitize(parsed.suggestions).slice(0, max);
  } catch (e) {
    console.warn("suggestion generation failed", e);
    return [];
  }
}

/** Data-aware fallback that needs no LLM, biased toward the given context. */
async function heuristicProfileSuggestions(profileId: string, context: SuggestContext): Promise<string[]> {
  const [skills, goals, topKc, quests, bank] = await Promise.all([
    db.skillState.findMany({ where: { profileId, skill: { not: "overall" } } }),
    db.goal.findMany({ where: { profileId, status: "ACTIVE" }, take: 2 }),
    db.killCountState.findFirst({ where: { profileId }, orderBy: { kc: "desc" } }),
    db.questState.findMany({ where: { profileId }, select: { quest: true, state: true } }),
    db.containerState.findUnique({ where: { profileId_container: { profileId, container: "BANK" } } }),
  ]);

  const closest = skills
    .filter((s) => s.level < 99)
    .map((s) => ({ ...s, remaining: xpForLevel(s.level + 1) - Number(s.xp) }))
    .filter((s) => s.remaining > 0) // guard against stale level vs xp drift
    .sort((a, b) => a.remaining - b.remaining)[0];
  const remainingQuests = quests.filter((q) => q.state !== "FINISHED" && !isMiniquest(q.quest));

  const goalPrompts = goals.map((g) => `What should I do this week toward "${g.title}"?`);
  const nextLevel = closest
    ? `What's the fastest way to get ${titleCase(closest.skill)} ${closest.level + 1}? Only ${formatXp(closest.remaining)} xp to go.`
    : null;
  const nextBoss = topKc ? `After ${topKc.kc} ${titleCase(topKc.boss)} kills, what boss should I learn next?` : null;
  const nextQuest = remainingQuests.length > 0 ? "Which quests should I knock out next, given my stats?" : null;

  // Context-specific ordering: the most relevant grounded prompts first, then
  // generic fillers to reach four.
  let ordered: (string | null)[];
  switch (context) {
    case "skills":
      ordered = [nextLevel, ...goalPrompts, "Which skills are lagging behind the rest of my account?", "How much XP did I gain this week, and in what?"];
      break;
    case "quests":
      ordered = [nextQuest, "Which quests unlock the most useful rewards for me?", "What am I missing for my next achievement diary tier?", ...goalPrompts];
      break;
    case "bank":
      ordered = [
        bank ? "What money-makers fit my current stats?" : null,
        "What gear upgrade should I buy next?",
        "What's the best value item I own that I'm not using?",
        "How has my bank value trended lately?",
      ];
      break;
    case "bosses":
      ordered = [nextBoss, "What gear upgrades would speed up my main boss?", "Which boss is most profitable at my stats?", ...goalPrompts];
      break;
    case "overview":
      ordered = [...goalPrompts, nextLevel, nextBoss, "What should I focus on in my next play session?"];
      break;
    default:
      ordered = [...goalPrompts, nextLevel, nextBoss, nextQuest];
  }

  const fillers = [
    "How much XP did I gain this week, and in what?",
    "What money-makers fit my current stats?",
    "What's a realistic goal for me this month?",
    "What should I focus on in my next play session?",
  ];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const s of [...ordered, ...fillers]) {
    if (s && !seen.has(s)) {
      seen.add(s);
      out.push(s);
    }
    if (out.length >= 4) break;
  }
  return out;
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
  const prompt = `Player context:\n${await buildContext(profileId)}\n\nThe player asked:\n${userMessage.slice(0, 1000)}\n\nThe assistant replied:\n${reply.slice(0, 2500)}\n\nGenerate exactly 3 likely followup messages.`;
  return llmSuggestions(FOLLOWUP_SYSTEM, prompt, 3);
}

async function readCache(
  profileId: string,
  context: SuggestContext,
): Promise<{ suggestions: string[]; ageMs: number } | null> {
  const row = await db.suggestionCache.findUnique({
    where: { profileId_context: { profileId, context } },
  });
  if (!row) return null;
  try {
    const suggestions = JSON.parse(row.payload) as string[];
    if (!Array.isArray(suggestions) || suggestions.length === 0) return null;
    return { suggestions, ageMs: Date.now() - row.updatedAt.getTime() };
  } catch {
    return null;
  }
}

async function writeCache(profileId: string, context: SuggestContext, suggestions: string[]): Promise<void> {
  const payload = JSON.stringify(suggestions);
  await db.suggestionCache.upsert({
    where: { profileId_context: { profileId, context } },
    create: { profileId, context, payload, updatedAt: new Date() },
    update: { payload, updatedAt: new Date() },
  });
}

/**
 * Instant read for the request path: never calls the LLM. Returns the best
 * suggestions available right now (fresh DB cache, else a stale cache, else
 * grounded heuristics) plus whether a background refresh should run.
 */
export async function peekSuggestions(
  profileId: string,
  context: SuggestContext = "chat",
): Promise<{ suggestions: string[]; needsRefresh: boolean }> {
  const cached = await readCache(profileId, context);
  if (cached && cached.ageMs < SUGGESTION_TTL_MS) {
    return { suggestions: cached.suggestions, needsRefresh: false };
  }
  // A stale LLM set still reads better than heuristics; only fall back to
  // heuristics when there is nothing cached at all.
  const suggestions = cached?.suggestions ?? (await heuristicProfileSuggestions(profileId, context));
  return { suggestions, needsRefresh: true };
}

/**
 * Slow path: heuristics upgraded by the LLM, persisted to the shared cache.
 * Safe to run in the background via after() or synchronously. Deduped so a
 * burst of tab loads doesn't stampede the model.
 */
export async function refreshSuggestions(
  profileId: string,
  context: SuggestContext = "chat",
): Promise<string[]> {
  const cached = await readCache(profileId, context);
  if (cached && cached.ageMs < REFRESH_MIN_INTERVAL_MS) return cached.suggestions;

  let suggestions = await heuristicProfileSuggestions(profileId, context);
  const focus = CONTEXT_FOCUS[context];
  const prompt = `Player context:\n${await buildContext(profileId)}\n\n${focus ? `${focus}\n\n` : ""}Generate exactly 4 starter questions.`;
  const fresh = await llmSuggestions(focus ? `${SUGGESTION_SYSTEM}\n\n${focus}` : SUGGESTION_SYSTEM, prompt, 4);
  if (fresh.length >= 2) suggestions = fresh;

  await writeCache(profileId, context, suggestions);
  return suggestions;
}

/**
 * Full synchronous result (LLM included). Used by non-request callers and
 * tests; the request path uses peekSuggestions + a background refresh.
 */
export async function suggestProfileQueries(
  profileId: string,
  context: SuggestContext = "chat",
): Promise<string[]> {
  const { suggestions, needsRefresh } = await peekSuggestions(profileId, context);
  return needsRefresh ? refreshSuggestions(profileId, context) : suggestions;
}
