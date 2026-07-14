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
export type SuggestContext = "chat" | "overview" | "skills" | "quests" | "bank" | "bosses" | "achievements";

export const SUGGEST_CONTEXTS: SuggestContext[] = [
  "chat",
  "overview",
  "skills",
  "quests",
  "bank",
  "bosses",
  "achievements",
];

const CONTEXT_FOCUS: Record<SuggestContext, string> = {
  chat: "",
  overview: "Focus on the player's headline progress: goals, weekly gains, and their most impactful next milestone.",
  skills: "Focus every question on skill training: the fastest routes to their next levels, efficient methods, and XP goals.",
  quests: "Focus on quests the player has NOT finished, quest rewards worth chasing, and achievement diary tiers still open. If all quests are done, do not mention quests or the quest cape.",
  bank: "Focus on the player's wealth: money-making methods that fit their stats, gear upgrades they could afford, and what to buy next.",
  bosses: "Focus on PvM: which boss to learn next given their kill counts, gear upgrades for bosses they already do, and kill-count milestones.",
  achievements: "Focus on completion progress: the next achievement diary tier within reach, combat achievement tiers to push for, and notable collection log slots to chase. Never propose a diary tier or CA tier the context shows is already complete.",
};

/** Shared structured-output LLM call (Anthropic or Gemini). null on failure. */
async function llmJson<T>(
  system: string,
  prompt: string,
  schema: Record<string, unknown>,
  maxTokens = 500,
): Promise<T | null> {
  if (!llmEnabled()) return null;
  try {
    if (anthropicEnabled()) {
      const client = new Anthropic();
      const response = await client.messages.create({
        model: "claude-haiku-4-5",
        max_tokens: maxTokens,
        output_config: { format: { type: "json_schema", schema } },
        system,
        messages: [{ role: "user", content: prompt }],
      });
      const text = response.content.find((b) => b.type === "text");
      if (!text || text.type !== "text") return null;
      return JSON.parse(text.text) as T;
    }
    return await runGeminiJson<T>({ system, prompt, schema, maxTokens });
  } catch (e) {
    console.warn("llmJson failed", e);
    return null;
  }
}

/** Shared LLM call for all suggestion flavors. Returns [] on any failure. */
async function llmSuggestions(system: string, prompt: string, max: number): Promise<string[]> {
  const parsed = await llmJson<{ suggestions?: string[] }>(system, prompt, {
    ...SUGGESTION_SCHEMA,
    additionalProperties: false,
  }, 400);
  return sanitize(parsed?.suggestions).slice(0, max);
}

// ------------------------------------------------------------------ goals

export type ProposedGoal = { title: string; rationale: string };

const GOAL_SYSTEM = `You propose meaningful, motivating Old School RuneScape account goals for a player based on their synced stats.

Grounding rules — violating any makes a goal worthless:
- Ground every goal in the player's real data from the context. Respect the account type: ironmen cannot use the Grand Exchange or trade, so never propose GE/bond/buying goals to them.
- Never propose content the context shows is already done: not a skill already 99, not a quest cape when all quests are complete, not a diary tier already finished.
- Read the account's build, not just individual levels: a lopsided combat profile is a deliberate build worth serving (e.g. 97 Strength with 1 Defence is a pure — recommend a max-pure or PK-oriented goal, never a goal that would raise Defence).
- Never propose a goal the player already has, or a trivial rephrasing of one — the context lists their existing goals. Recommend complementary directions and fill gaps those goals leave open.
- Mix time horizons across the set: one achievable soon, one medium-term, one aspirational — all calibrated to their current levels and progress.
- Prefer goals the assistant can steer toward and track: skill-level targets, boss kill-count milestones, quest/diary/collection-log completion, or a wealth target.

Each goal has a short imperative title (max 55 chars, e.g. "Reach 99 Slayer") and a one-sentence rationale grounded in their stats (max 120 chars).`;

const GOAL_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: {
    goals: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        properties: {
          title: { type: "string" },
          rationale: { type: "string" },
        },
        required: ["title", "rationale"],
      },
    },
  },
  required: ["goals"],
};

/** Normalize a goal title for dedupe: case/punctuation/whitespace-insensitive. */
function goalKey(title: string): string {
  return title.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function sanitizeGoals(raw: ProposedGoal[] | undefined, count: number, exclude?: Set<string>): ProposedGoal[] {
  const seen = new Set(exclude ?? []);
  const out: ProposedGoal[] = [];
  for (const g of raw ?? []) {
    if (!g || typeof g.title !== "string" || g.title.trim().length === 0) continue;
    const title = g.title.trim().slice(0, 80);
    const key = goalKey(title);
    if (seen.has(key)) continue; // drop duplicates and anything the player already has
    seen.add(key);
    out.push({
      title,
      rationale: (typeof g.rationale === "string" ? g.rationale : "").trim().slice(0, 160),
    });
    if (out.length >= count) break;
  }
  return out;
}

/**
 * Generate grounded goal proposals from a prebuilt context string. Pass the
 * player's existing goal titles to exclude them (the on-demand recommender
 * flow), so the model never echoes a goal they already have.
 */
export async function generateGoals(
  context: string,
  options: { count?: number; existingTitles?: string[] } = {},
): Promise<ProposedGoal[]> {
  const count = options.count ?? 3;
  const existing = options.existingTitles?.filter((t) => t.trim().length > 0) ?? [];
  const avoid = existing.length
    ? `\n\nThe player already has these goals — do not repeat or rephrase any of them:\n${existing.map((t) => `- ${t}`).join("\n")}`
    : "";
  const parsed = await llmJson<{ goals?: ProposedGoal[] }>(
    GOAL_SYSTEM,
    `Player context:\n${context}${avoid}\n\nPropose exactly ${count} account goals.`,
    GOAL_SCHEMA,
    // Generous budget: Gemini's default-on thinking shares this with the
    // output, so a tight cap truncates the JSON to nothing.
    2048,
  );
  return sanitizeGoals(parsed?.goals, count, new Set(existing.map(goalKey)));
}

/**
 * Proposed goals for an authenticated profile, persisted in the shared cache
 * so onboarding stays instant and we don't re-spend on every visit. Callers
 * should only surface these when the profile has few/no goals of its own.
 */
export async function proposeGoals(profileId: string): Promise<ProposedGoal[]> {
  const row = await db.suggestionCache.findUnique({
    where: { profileId_context: { profileId, context: "goals" } },
  });
  if (row) {
    try {
      const goals = JSON.parse(row.payload) as ProposedGoal[];
      if (Array.isArray(goals) && goals.length > 0) return goals;
    } catch {
      /* regenerate below */
    }
  }
  const goals = await generateGoals(await buildContext(profileId));
  if (goals.length > 0) {
    const payload = JSON.stringify(goals);
    await db.suggestionCache.upsert({
      where: { profileId_context: { profileId, context: "goals" } },
      create: { profileId, context: "goals", payload, updatedAt: new Date() },
      update: { payload, updatedAt: new Date() },
    });
  }
  return goals;
}

const RECOMMEND_CONTEXT = "goal-recs";
const RECOMMEND_COUNT = 4;

/**
 * On-demand goal recommendations for an account that already has goals of its
 * own. Unlike {@link proposeGoals} (the one-time onboarding nudge), this reads
 * the player's current goals so it never suggests something they already have
 * and instead fills the gaps around them. Results are cached so re-opening the
 * panel is instant; pass `refresh` to regenerate a fresh set on demand.
 */
export async function recommendGoals(
  profileId: string,
  { refresh = false }: { refresh?: boolean } = {},
): Promise<ProposedGoal[]> {
  const existing = await db.goal.findMany({
    where: { profileId, status: "ACTIVE" },
    select: { title: true },
  });
  const existingKeys = new Set(existing.map((g) => goalKey(g.title)));

  if (!refresh) {
    const row = await db.suggestionCache.findUnique({
      where: { profileId_context: { profileId, context: RECOMMEND_CONTEXT } },
    });
    if (row) {
      try {
        const cached = JSON.parse(row.payload) as ProposedGoal[];
        // Drop any cached rec the player has since added as a real goal.
        const fresh = Array.isArray(cached)
          ? cached.filter((g) => g?.title && !existingKeys.has(goalKey(g.title)))
          : [];
        if (fresh.length > 0) return fresh;
      } catch {
        /* regenerate below */
      }
    }
  }

  const goals = await generateGoals(await buildContext(profileId), {
    count: RECOMMEND_COUNT,
    existingTitles: existing.map((g) => g.title),
  });
  if (goals.length > 0) {
    const payload = JSON.stringify(goals);
    await db.suggestionCache.upsert({
      where: { profileId_context: { profileId, context: RECOMMEND_CONTEXT } },
      create: { profileId, context: RECOMMEND_CONTEXT, payload, updatedAt: new Date() },
      update: { payload, updatedAt: new Date() },
    });
  }
  return goals;
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
    case "achievements":
      ordered = [
        "What's the closest achievement diary tier I can finish?",
        "Which combat achievement tier should I push for next?",
        "What collection log items are realistic for me to chase?",
        ...goalPrompts,
      ];
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
const SPEAK_SYSTEM = `You rewrite an assistant's full written reply into a brief spoken response for text-to-speech.

Rules:
- Include ONLY the key answer to the user's question. Drop tables, lists, step-by-step detail, caveats, and anything that belongs in the written version.
- 1-2 short sentences, at most ~45 words. Natural and conversational to hear aloud.
- No markdown, no bullet points, no emoji, no URLs. Write numbers as words where it reads better (e.g. "about one point two million").
- If the reply is already a short sentence, lightly tighten it for speech.`;

/**
 * Condenses a full reply into a short line optimized for narration. The full
 * text still shows in the transcript; only this is spoken. Falls back to a
 * markdown-stripped clip of the reply if the LLM is unavailable.
 */
export async function summarizeForSpeech(userMessage: string, reply: string): Promise<string> {
  const parsed = await llmJson<{ spoken?: string }>(
    SPEAK_SYSTEM,
    `User asked:\n${userMessage.slice(0, 600)}\n\nAssistant reply:\n${reply.slice(0, 2500)}\n\nProduce the spoken version.`,
    {
      type: "object",
      additionalProperties: false,
      properties: { spoken: { type: "string" } },
      required: ["spoken"],
    },
    800,
  );
  const spoken = (parsed?.spoken ?? "").trim();
  if (spoken) return spoken.slice(0, 600);
  // Fallback: strip markdown and clip to the first couple of sentences.
  const plain = reply.replace(/[*_#`>|]/g, "").replace(/\s+/g, " ").trim();
  const sentences = plain.split(/(?<=[.!?])\s+/).slice(0, 2).join(" ");
  return sentences.slice(0, 400);
}

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
