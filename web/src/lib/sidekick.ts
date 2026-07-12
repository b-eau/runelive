// The Sidekick brain: account-aware context + tools for the LLM chat and
// voice interfaces. Tools are bound to a profile so the model can only ever
// read the signed-in user's own data.

import Anthropic from "@anthropic-ai/sdk";
import { betaTool } from "@anthropic-ai/sdk/helpers/beta/json-schema";
import { db } from "./db";
import { geminiEnabled, runGeminiChat } from "./gemini";
import { formatGp, formatXp, levelForXp, titleCase, xpForLevel } from "./osrs";
import { isMiniquest } from "./quests";

export const SIDEKICK_MODEL = "claude-opus-4-8";

export function anthropicEnabled(): boolean {
  return !!process.env.ANTHROPIC_API_KEY;
}

/** Anthropic takes precedence when both keys are configured. */
export function llmEnabled(): boolean {
  return anthropicEnabled() || geminiEnabled();
}

/** Compact account summary injected up-front so common questions need no tool call. */
export async function buildContext(profileId: string): Promise<string> {
  const [profile, skills, goals, quests, diaries, bank, topKc, combatAch, clog] = await Promise.all([
    db.profile.findUnique({ where: { id: profileId }, include: { account: true } }),
    db.skillState.findMany({ where: { profileId } }),
    db.goal.findMany({ where: { profileId, status: "ACTIVE" } }),
    db.questState.findMany({ where: { profileId } }),
    db.diaryState.findMany({ where: { profileId } }),
    db.containerState.findUnique({ where: { profileId_container: { profileId, container: "BANK" } } }),
    db.killCountState.findMany({ where: { profileId }, orderBy: { kc: "desc" }, take: 8 }),
    db.combatAchievementState.findUnique({ where: { profileId } }),
    db.collectionLogState.findUnique({ where: { profileId } }),
  ]);
  if (!profile) return "";

  const skillLines = skills
    .filter((s) => s.skill !== "overall")
    .sort((a, b) => b.level - a.level)
    .map((s) => `${titleCase(s.skill)} ${s.level} (${formatXp(s.xp)} xp)`)
    .join(", ");
  const overall = skills.find((s) => s.skill === "overall");

  // Miniquests award no quest points and don't gate the Quest point cape —
  // report them separately so the model never conflates the two.
  const fullQuests = quests.filter((q) => !isMiniquest(q.quest));
  const minis = quests.filter((q) => isMiniquest(q.quest));
  const fullDone = fullQuests.filter((q) => q.state === "FINISHED").length;
  const miniDone = minis.filter((q) => q.state === "FINISHED").length;
  const remainingFull = fullQuests.filter((q) => q.state !== "FINISHED").map((q) => q.quest);
  const questLine =
    quests.length === 0
      ? "Quest data not synced yet."
      : remainingFull.length === 0
        ? `Quests: ALL ${fullDone} quests complete — the Quest point cape is unlocked. Miniquests (award no quest points, never gate the quest cape): ${miniDone}/${minis.length} complete${minis.length - miniDone > 0 ? `, remaining: ${minis.filter((q) => q.state !== "FINISHED").map((q) => q.quest).join(", ")}` : ""}.`
        : `Quests: ${fullDone}/${fullQuests.length} complete (${remainingFull.length} remaining). Miniquests (separate; no quest points): ${miniDone}/${minis.length} complete.`;

  const diaryByArea = new Map<string, string[]>();
  for (const d of diaries) {
    if (!d.completed) continue;
    if (!diaryByArea.has(d.area)) diaryByArea.set(d.area, []);
    diaryByArea.get(d.area)!.push(titleCase(d.tier.toLowerCase()));
  }
  const diaryLine =
    diaries.length === 0
      ? ""
      : diaryByArea.size === 0
        ? "Achievement diaries: none complete yet."
        : `Achievement diaries complete: ${[...diaryByArea.entries()].map(([area, tiers]) => `${area} (${tiers.join("/")})`).join(", ")}.`;

  const caTiers = combatAch
    ? (["grandmaster", "master", "elite", "hard", "medium", "easy"] as const).filter((t) => combatAch[t])
    : [];
  const caLine = combatAch
    ? `Combat Achievements: ${combatAch.points} points; tiers complete: ${caTiers.length ? caTiers.map(titleCase).reverse().join(", ") : "none"}.`
    : "";
  const clogLine = clog ? `Collection log: ${clog.obtained}/${clog.total} uniques obtained.` : "";

  return [
    `Player: ${profile.account.displayName} — ${profile.kind} profile, account type ${profile.accountType}, combat level ${profile.combatLevel ?? "unknown"}.`,
    overall ? `Total level ${overall.level}, total XP ${formatXp(overall.xp)}.` : "",
    `Skills: ${skillLines}.`,
    questLine,
    diaryLine,
    caLine,
    clogLine,
    bank ? `Bank value ≈ ${formatGp(bank.value)} gp.` : "Bank not synced yet.",
    topKc.length ? `Top boss KC: ${topKc.map((k) => `${titleCase(k.boss)} ${k.kc}`).join(", ")}.` : "",
    goals.length
      ? `The player's stated account goals (steer all advice toward these): ${goals.map((g) => `"${g.title}"${g.notes ? ` — ${g.notes}` : ""}`).join("; ")}.`
      : "The player has not set explicit goals yet — when natural, suggest they add one.",
  ]
    .filter(Boolean)
    .join("\n");
}

export function buildTools(profileId: string) {
  const searchBank = betaTool({
    name: "search_bank",
    description:
      "Search the player's bank, inventory, and equipped items by name substring. Returns matching items with quantities and GE values. Call this whenever the player asks what they own, whether they can afford something, or what gear they have.",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Case-insensitive item name substring, e.g. 'rune' or 'dharok'" },
      },
      required: ["query"],
    },
    run: async (input) => {
      const { query } = input as { query: string };
      const containers = await db.containerState.findMany({ where: { profileId } });
      const ids = new Set<number>();
      const parsed = containers.map((c) => {
        const items = JSON.parse(c.items) as { id: number; qty: number }[];
        items.forEach((i) => ids.add(i.id));
        return { container: c.container, items };
      });
      const prices = await db.itemPrice.findMany({ where: { itemId: { in: [...ids] } } });
      const byId = new Map(prices.map((p) => [p.itemId, p]));
      const q = query.toLowerCase();
      const hits: string[] = [];
      for (const c of parsed) {
        for (const item of c.items) {
          const meta = byId.get(item.id);
          const name = meta?.name ?? `Item #${item.id}`;
          if (!name.toLowerCase().includes(q)) continue;
          const value = (item.id === 995 ? 1 : (meta?.price ?? 0)) * item.qty;
          hits.push(`${name} ×${item.qty.toLocaleString()} [${c.container.toLowerCase()}]${value ? ` ≈ ${formatGp(value)} gp` : ""}`);
        }
      }
      return hits.length ? hits.slice(0, 50).join("\n") : `No items matching "${query}".`;
    },
  });

  const viewQuestLog = betaTool({
    name: "view_quest_log",
    description:
      "Read the player's quest log. Filter by state: FINISHED, IN_PROGRESS, or NOT_STARTED. Call this before giving quest advice.",
    inputSchema: {
      type: "object",
      properties: {
        state: {
          type: "string",
          enum: ["FINISHED", "IN_PROGRESS", "NOT_STARTED", "ALL"],
          description: "Which quests to list",
        },
      },
      required: ["state"],
    },
    run: async (input) => {
      const { state } = input as { state: string };
      const quests = await db.questState.findMany({
        where: { profileId, ...(state !== "ALL" ? { state } : {}) },
        orderBy: { quest: "asc" },
      });
      if (quests.length === 0) return "No quests match (quest data may not be synced yet).";
      return quests
        .map((q) => {
          const mini = isMiniquest(q.quest) ? " [miniquest — no quest points, doesn't gate the quest cape]" : "";
          return state === "ALL" ? `${q.quest} — ${q.state}${mini}` : `${q.quest}${mini}`;
        })
        .join("\n");
    },
  });

  const viewDiaries = betaTool({
    name: "view_achievement_diaries",
    description: "Read the player's achievement diary completion per area and tier.",
    inputSchema: { type: "object", properties: {}, required: [] },
    run: async () => {
      const diaries = await db.diaryState.findMany({ where: { profileId } });
      if (diaries.length === 0) return "No diary data synced yet.";
      const byArea = new Map<string, string[]>();
      for (const d of diaries) {
        if (!byArea.has(d.area)) byArea.set(d.area, []);
        if (d.completed) byArea.get(d.area)!.push(d.tier);
      }
      return [...byArea.entries()]
        .map(([area, tiers]) => `${area}: ${tiers.length ? tiers.join(", ") : "none complete"}`)
        .join("\n");
    },
  });

  const viewKc = betaTool({
    name: "view_boss_kill_counts",
    description: "Read the player's boss kill counts, highest first.",
    inputSchema: { type: "object", properties: {}, required: [] },
    run: async () => {
      const kcs = await db.killCountState.findMany({ where: { profileId }, orderBy: { kc: "desc" } });
      if (kcs.length === 0) return "No boss kill counts tracked yet.";
      return kcs.map((k) => `${titleCase(k.boss)}: ${k.kc}`).join("\n");
    },
  });

  const xpGains = betaTool({
    name: "view_xp_gains",
    description:
      "Compute XP gained per skill over a trailing window of days (e.g. 7, 30, 365). Use for questions about progress, pace, or 'how much X have I trained lately'.",
    inputSchema: {
      type: "object",
      properties: {
        days: { type: "integer", description: "Trailing window in days (1-730)" },
      },
      required: ["days"],
    },
    run: async (input) => {
      const days = Math.min(Math.max((input as { days: number }).days, 1), 730);
      const since = new Date(Date.now() - days * 86400_000);
      const rows = await db.xpSample.findMany({
        where: { profileId, date: { gte: since } },
        orderBy: { date: "asc" },
      });
      const bySkill = new Map<string, { first: bigint; last: bigint }>();
      for (const r of rows) {
        const e = bySkill.get(r.skill);
        if (!e) bySkill.set(r.skill, { first: r.xp, last: r.xp });
        else e.last = r.xp;
      }
      const gains = [...bySkill.entries()]
        .map(([skill, { first, last }]) => ({ skill, gained: Number(last - first) }))
        .filter((g) => g.gained > 0 && g.skill !== "overall")
        .sort((a, b) => b.gained - a.gained);
      if (gains.length === 0) return `No XP gained in the last ${days} days.`;
      return gains.map((g) => `${titleCase(g.skill)}: +${formatXp(g.gained)}`).join("\n");
    },
  });

  const viewCombatAchievements = betaTool({
    name: "view_combat_achievements",
    description:
      "Read the player's combat achievement progress: points, which tiers are complete, and points remaining to each incomplete tier.",
    inputSchema: { type: "object", properties: {}, required: [] },
    run: async () => {
      const ca = await db.combatAchievementState.findUnique({ where: { profileId } });
      if (!ca) return "No combat achievement data synced yet.";
      const thresholds = JSON.parse(ca.thresholds) as Record<string, number>;
      const tiers = ["EASY", "MEDIUM", "HARD", "ELITE", "MASTER", "GRANDMASTER"] as const;
      const lines = tiers.map((tier) => {
        const t = thresholds[tier] ?? 0;
        const complete = ca[tier.toLowerCase() as Lowercase<typeof tier>];
        return `${titleCase(tier.toLowerCase())} (${t} pts): ${complete ? "COMPLETE" : t > 0 ? `${t - ca.points} points to go` : "unknown"}`;
      });
      return [`Total points: ${ca.points}`, ...lines].join("\n");
    },
  });

  const viewCollectionLog = betaTool({
    name: "view_collection_log",
    description:
      "Read the player's collection log progress: unique slots obtained overall and per section (bosses, raids, clues, minigames, other). Individual item slots are not tracked — only counts.",
    inputSchema: { type: "object", properties: {}, required: [] },
    run: async () => {
      const clog = await db.collectionLogState.findUnique({ where: { profileId } });
      if (!clog) return "No collection log data synced yet.";
      const sections = JSON.parse(clog.sections) as Record<string, { obtained: number; total: number }>;
      const lines = Object.entries(sections).map(
        ([name, c]) => `${titleCase(name)}: ${c.obtained}/${c.total}`,
      );
      return [`Overall uniques: ${clog.obtained}/${clog.total}`, ...lines].join("\n");
    },
  });

  const skillTarget = betaTool({
    name: "calc_skill_target",
    description:
      "Skill progress calculator: computes exact XP remaining from the player's current XP to a target level. Use whenever the player asks how far a level is, how long something will take, or when planning goals — never estimate XP math yourself.",
    inputSchema: {
      type: "object",
      properties: {
        skill: { type: "string", description: "Skill name, e.g. 'herblore'" },
        target_level: { type: "integer", description: "Target level (2-99, or up to 126 virtual)" },
      },
      required: ["skill", "target_level"],
    },
    run: async (input) => {
      const { skill, target_level } = input as { skill: string; target_level: number };
      const state = await db.skillState.findUnique({
        where: { profileId_skill: { profileId, skill: skill.toLowerCase().trim() } },
      });
      if (!state) return `No synced data for skill "${skill}".`;
      const target = Math.min(Math.max(target_level, 2), 126);
      const currentXp = Number(state.xp);
      const targetXp = xpForLevel(target);
      const remaining = targetXp - currentXp;
      if (remaining <= 0) {
        return `${titleCase(state.skill)} is already level ${levelForXp(currentXp)} (${formatXp(currentXp)} xp) — target ${target} (${formatXp(targetXp)} xp) is met.`;
      }
      return `${titleCase(state.skill)}: level ${state.level} with ${currentXp.toLocaleString()} xp. Level ${target} needs ${targetXp.toLocaleString()} xp — ${remaining.toLocaleString()} xp remaining.`;
    },
  });

  const lookupItem = betaTool({
    name: "lookup_item",
    description:
      "Look up any OSRS item by name (owned or not): live GE guide price, high alch value, members status, and examine text. Use for gear-upgrade advice, affordability checks, and money-making math.",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Item name or substring, e.g. 'dragon warhammer'" },
      },
      required: ["query"],
    },
    run: async (input) => {
      const { query } = input as { query: string };
      const items = await db.itemPrice.findMany({
        where: { name: { contains: query.trim(), mode: "insensitive" } },
        orderBy: { price: "desc" },
        take: 8,
      });
      if (items.length === 0) return `No items matching "${query}" in the catalog.`;
      return items
        .map((i) => {
          const bits = [
            i.price > 0 ? `GE ≈ ${formatGp(i.price)} gp` : "untradeable or no GE price",
            i.highAlch ? `high alch ${formatGp(i.highAlch)} gp` : "",
            i.members === false ? "F2P" : i.members ? "members" : "",
          ].filter(Boolean);
          return `${i.name} — ${bits.join(", ")}${i.examine ? ` — "${i.examine}"` : ""}`;
        })
        .join("\n");
    },
  });

  return [
    searchBank,
    viewQuestLog,
    viewDiaries,
    viewKc,
    xpGains,
    viewCombatAchievements,
    viewCollectionLog,
    skillTarget,
    lookupItem,
  ];
}

export function systemPrompt(context: string): string {
  return `You are Sidekick, an expert Old School RuneScape companion embedded in the OSRS Sidekick web app. You have live synced data about the player's account, provided below, plus tools to look up their bank, quest log, diaries, boss kill counts, and XP gains.

Ground rules:
- Steer every recommendation toward the player's stated goals. If advice conflicts with a goal (e.g. suggesting bond purchases to a "no bonds" account, or tradeable shortcuts to an ironman), respect the goal.
- Respect the account type: ironmen cannot use the Grand Exchange or trade.
- Use the tools when the answer depends on the player's actual data rather than guessing. Check the quest log before recommending quests they may have finished.
- Be concrete: name the quest, the gear, the training method, the XP rates. OSRS players want specifics.
- Keep responses tight and skimmable. This may be read aloud by a voice interface, so avoid tables and heavy markdown; short paragraphs and simple lists only.

Player context (synced ${new Date().toISOString().slice(0, 10)}):
${context}`;
}

export async function runSidekick(
  profileId: string,
  history: { role: "user" | "assistant"; content: string }[],
): Promise<string> {
  const context = await buildContext(profileId);

  if (!llmEnabled()) {
    return demoReply(context);
  }

  if (!anthropicEnabled()) {
    const text = await runGeminiChat({
      system: systemPrompt(context),
      history,
      tools: buildTools(profileId),
      maxIterations: 8,
    });
    return text || "Hmm, I came up empty — try rephrasing that.";
  }

  const client = new Anthropic();
  const finalMessage = await client.beta.messages.toolRunner({
    model: SIDEKICK_MODEL,
    max_tokens: 4096,
    thinking: { type: "adaptive" },
    system: systemPrompt(context),
    tools: buildTools(profileId),
    messages: history,
    max_iterations: 8,
  });

  const text = finalMessage.content
    .filter((b) => b.type === "text")
    .map((b) => (b as { text: string }).text)
    .join("\n")
    .trim();
  return text || "Hmm, I came up empty — try rephrasing that.";
}

/** Keeps local demos functional without an API key. */
function demoReply(context: string): string {
  return [
    "⚠️ Demo mode — set ANTHROPIC_API_KEY or GEMINI_API_KEY in web/.env to enable the real Sidekick assistant.",
    "",
    "Here's the account context I would reason over:",
    context,
  ].join("\n");
}
