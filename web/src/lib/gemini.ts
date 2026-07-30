// Gemini backend for the Sidekick chat, used when GEMINI_API_KEY is set
// (and ANTHROPIC_API_KEY is not). Talks to the public generateContent REST
// API via fetch — no SDK dependency.
//
// Gemini 3 models attach thoughtSignature fields to response parts during
// function calling and require them back verbatim on later turns, so the
// tool loop pushes each model turn into the conversation unmodified.
//
// Two tiers, mirroring the Anthropic side (Opus for the authenticated chat,
// Haiku for guest chat and structured-output calls):
//   3.5 Flash      — the tool-using Sidekick chat, where reasoning depth pays.
//   3.5 Flash-Lite — guest chat plus the high-volume JSON calls (starter
//                    suggestions, followups, goal proposals, speech clips).
//
// Note: temperature / topP / topK are deprecated on Gemini 3.x and the API
// errors on them in newer generations — never send them.

// `||` not `??`: .env templates leave these as empty strings.
export const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-3.5-flash";
export const GEMINI_LITE_MODEL = process.env.GEMINI_LITE_MODEL || "gemini-3.5-flash-lite";

// 3.5 accepts "minimal" | "low" | "medium" | "high"; both models default to
// more thinking than we want here. The sidekick tool loop makes several
// sequential calls per turn, so per-call latency compounds — "low" keeps chat
// turns snappy (see REQUEST_TIMEOUT_MS / deadlineMs below). Schema-shaped
// extraction on the lite tier needs less than that.
const THINKING_LEVEL = process.env.GEMINI_THINKING_LEVEL || "low";
const LITE_THINKING_LEVEL = process.env.GEMINI_LITE_THINKING_LEVEL || "minimal";
const REQUEST_TIMEOUT_MS = 45_000;

function thinkingLevelFor(model: string): string {
  return model === GEMINI_LITE_MODEL ? LITE_THINKING_LEVEL : THINKING_LEVEL;
}

export function geminiEnabled(): boolean {
  return !!process.env.GEMINI_API_KEY;
}

/** Structural match for the tools built with the Anthropic SDK's betaTool. */
type RunnableTool = {
  name: string;
  description?: string;
  input_schema?: { properties?: unknown };
  run: (args: never) => unknown;
};

type GeminiPart = {
  text?: string;
  thought?: boolean;
  thoughtSignature?: string;
  functionCall?: { name: string; args?: Record<string, unknown> };
  functionResponse?: { name: string; response: Record<string, unknown> };
};

type GeminiContent = { role: "user" | "model"; parts: GeminiPart[] };

async function generateContent(
  model: string,
  body: Record<string, unknown>,
): Promise<GeminiContent | null> {
  const call = async (payload: Record<string, unknown>) =>
    fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": process.env.GEMINI_API_KEY!,
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });

  let res = await call(body);
  if (res.status === 400) {
    // Thinking knobs drift across model generations; if the API rejects
    // ours, degrade to default thinking rather than failing the turn.
    const text = await res.text();
    const config = body.generationConfig as Record<string, unknown> | undefined;
    if (/thinking/i.test(text) && config?.thinkingConfig) {
      const { thinkingConfig: _dropped, ...rest } = config;
      console.warn(`Gemini (${model}) rejected thinkingConfig, retrying without it`);
      res = await call({ ...body, generationConfig: rest });
    } else {
      throw new Error(`Gemini API 400 (${model}): ${text.slice(0, 500)}`);
    }
  }
  if (!res.ok) {
    throw new Error(`Gemini API ${res.status} (${model}): ${(await res.text()).slice(0, 500)}`);
  }
  const data = (await res.json()) as { candidates?: { content?: GeminiContent }[] };
  return data.candidates?.[0]?.content ?? null;
}

function textOf(content: GeminiContent | null): string {
  return (content?.parts ?? [])
    .filter((p) => p.text && !p.thought)
    .map((p) => p.text)
    .join("\n")
    .trim();
}

/**
 * Chat turn with an optional client-side tool loop. Returns the final text
 * ("" if the model produced none — callers supply their own fallback).
 *
 * Defaults to the 3.5 Flash tier; pass `model: GEMINI_LITE_MODEL` for cheap,
 * tool-less turns such as guest chat.
 */
export async function runGeminiChat(opts: {
  system: string;
  history: { role: "user" | "assistant"; content: string }[];
  tools?: RunnableTool[];
  maxTokens?: number;
  maxIterations?: number;
  /** Overall turn budget; the tool loop stops starting new calls near it. */
  deadlineMs?: number;
  model?: string;
}): Promise<string> {
  const model = opts.model ?? GEMINI_MODEL;
  const thinkingLevel = thinkingLevelFor(model);
  const startedAt = Date.now();
  const deadlineMs = opts.deadlineMs ?? 90_000;
  const contents: GeminiContent[] = opts.history.map((m) => ({
    role: m.role === "assistant" ? "model" : "user",
    parts: [{ text: m.content }],
  }));

  const tools = opts.tools ?? [];
  const byName = new Map(tools.map((t) => [t.name, t]));
  const declarations = tools.map((t) => ({
    name: t.name,
    description: t.description,
    // Gemini rejects OBJECT schemas with no properties — omit instead.
    ...(Object.keys((t.input_schema?.properties as object | undefined) ?? {}).length > 0
      ? { parameters: t.input_schema }
      : {}),
  }));

  for (let i = 0; i < (opts.maxIterations ?? 8); i++) {
    // Only start a call that can still finish inside the turn budget.
    if (Date.now() - startedAt > deadlineMs - REQUEST_TIMEOUT_MS) {
      console.warn(`Gemini tool loop hit the ${deadlineMs}ms turn deadline after ${i} calls`);
      break;
    }
    const content = await generateContent(model, {
      systemInstruction: { parts: [{ text: opts.system }] },
      contents,
      ...(declarations.length > 0 ? { tools: [{ functionDeclarations: declarations }] } : {}),
      generationConfig: {
        maxOutputTokens: opts.maxTokens ?? 4096,
        thinkingConfig: { thinkingLevel },
      },
    });
    if (!content?.parts?.length) return textOf(content);

    const calls = content.parts.filter((p) => p.functionCall);
    if (calls.length === 0) return textOf(content);

    contents.push(content); // verbatim, thoughtSignatures included
    const results: GeminiPart[] = [];
    for (const part of calls) {
      const { name, args } = part.functionCall!;
      const tool = byName.get(name);
      let result: string;
      try {
        result = tool
          ? String(await tool.run((args ?? {}) as never))
          : `Unknown tool "${name}".`;
      } catch (e) {
        result = `Tool "${name}" failed: ${e instanceof Error ? e.message : String(e)}`;
      }
      results.push({ functionResponse: { name, response: { result } } });
    }
    contents.push({ role: "user", parts: results });
  }
  return "";
}

/**
 * Single-shot structured output against a standard JSON schema, on the cheap
 * 3.5 Flash-Lite tier. Token budgets must still leave headroom:
 * maxOutputTokens includes thinking tokens, and running out truncates the
 * response to prose that won't parse.
 */
export async function runGeminiJson<T>(opts: {
  system: string;
  prompt: string;
  schema: Record<string, unknown>;
  maxTokens?: number;
  model?: string;
}): Promise<T> {
  const model = opts.model ?? GEMINI_LITE_MODEL;
  const content = await generateContent(model, {
    systemInstruction: { parts: [{ text: opts.system }] },
    contents: [{ role: "user", parts: [{ text: opts.prompt }] }],
    generationConfig: {
      maxOutputTokens: opts.maxTokens ?? 2048,
      responseMimeType: "application/json",
      responseJsonSchema: opts.schema,
      thinkingConfig: { thinkingLevel: thinkingLevelFor(model) },
    },
  });
  return JSON.parse(textOf(content)) as T;
}
