// Gemini backend for the Sidekick chat, used when GEMINI_API_KEY is set
// (and ANTHROPIC_API_KEY is not). Talks to the public generateContent REST
// API via fetch — no SDK dependency.
//
// Gemini 3 models attach thoughtSignature fields to response parts during
// function calling and require them back verbatim on later turns, so the
// tool loop pushes each model turn into the conversation unmodified.

// `||` not `??`: .env templates leave these as empty strings.
export const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-3.5-flash";

// Thinking is on by default and slow; "low" keeps chat turns snappy. The
// sidekick tool loop makes several sequential calls per turn, so per-call
// latency compounds — see REQUEST_TIMEOUT_MS / deadlineMs below.
const THINKING_LEVEL = process.env.GEMINI_THINKING_LEVEL || "low";
const REQUEST_TIMEOUT_MS = 45_000;

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

async function generateContent(body: Record<string, unknown>): Promise<GeminiContent | null> {
  const call = async (payload: Record<string, unknown>) =>
    fetch(`https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`, {
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
      console.warn("Gemini rejected thinkingConfig, retrying without it");
      res = await call({ ...body, generationConfig: rest });
    } else {
      throw new Error(`Gemini API 400: ${text.slice(0, 500)}`);
    }
  }
  if (!res.ok) {
    throw new Error(`Gemini API ${res.status}: ${(await res.text()).slice(0, 500)}`);
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
 */
export async function runGeminiChat(opts: {
  system: string;
  history: { role: "user" | "assistant"; content: string }[];
  tools?: RunnableTool[];
  maxTokens?: number;
  maxIterations?: number;
  /** Overall turn budget; the tool loop stops starting new calls near it. */
  deadlineMs?: number;
}): Promise<string> {
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
    const content = await generateContent({
      systemInstruction: { parts: [{ text: opts.system }] },
      contents,
      ...(declarations.length > 0 ? { tools: [{ functionDeclarations: declarations }] } : {}),
      generationConfig: {
        maxOutputTokens: opts.maxTokens ?? 4096,
        thinkingConfig: { thinkingLevel: THINKING_LEVEL },
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
 * Single-shot structured output against a standard JSON schema. Token
 * budgets must leave headroom: maxOutputTokens includes the model's
 * (default-on) thinking tokens, and running out truncates to prose.
 */
export async function runGeminiJson<T>(opts: {
  system: string;
  prompt: string;
  schema: Record<string, unknown>;
  maxTokens?: number;
}): Promise<T> {
  const content = await generateContent({
    systemInstruction: { parts: [{ text: opts.system }] },
    contents: [{ role: "user", parts: [{ text: opts.prompt }] }],
    generationConfig: {
      maxOutputTokens: opts.maxTokens ?? 2048,
      responseMimeType: "application/json",
      responseJsonSchema: opts.schema,
      thinkingConfig: { thinkingLevel: THINKING_LEVEL },
    },
  });
  return JSON.parse(textOf(content)) as T;
}
