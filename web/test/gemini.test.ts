// Gemini backend wiring: which model each entry point talks to, the thinking
// level it asks for, and the degradation path when the API rejects it. The
// real API is never called — fetch is stubbed per test.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  GEMINI_LITE_MODEL,
  GEMINI_MODEL,
  runGeminiChat,
  runGeminiJson,
} from "@/lib/gemini";

type Call = { model: string; body: Record<string, never> };

const calls: Call[] = [];
const realFetch = globalThis.fetch;
const previousKey = process.env.GEMINI_API_KEY;

/** Records each request and replies with a plain text part. */
function stubFetch(reply: (call: Call) => Response) {
  globalThis.fetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const model = String(url).match(/models\/([^:]+):/)?.[1] ?? "";
    const call = { model, body: JSON.parse(String(init?.body)) };
    calls.push(call);
    return reply(call);
  }) as unknown as typeof fetch;
}

function textResponse(text: string): Response {
  return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text }] } }] }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function thinkingLevelOf(call: Call): string {
  const config = call.body as unknown as {
    generationConfig?: { thinkingConfig?: { thinkingLevel?: string } };
  };
  return config.generationConfig?.thinkingConfig?.thinkingLevel ?? "";
}

beforeEach(() => {
  calls.length = 0;
  process.env.GEMINI_API_KEY = "test-key";
});

afterEach(() => {
  globalThis.fetch = realFetch;
  if (previousKey === undefined) delete process.env.GEMINI_API_KEY;
  else process.env.GEMINI_API_KEY = previousKey;
});

describe("model tiers", () => {
  it("defaults to 3.5 Flash and 3.5 Flash-Lite", () => {
    expect(GEMINI_MODEL).toBe("gemini-3.5-flash");
    expect(GEMINI_LITE_MODEL).toBe("gemini-3.5-flash-lite");
  });

  it("runs chat on Flash with low thinking", async () => {
    stubFetch(() => textResponse("hello"));
    await expect(runGeminiChat({ system: "s", history: [{ role: "user", content: "hi" }] }))
      .resolves.toBe("hello");
    expect(calls).toHaveLength(1);
    expect(calls[0].model).toBe(GEMINI_MODEL);
    expect(thinkingLevelOf(calls[0])).toBe("low");
  });

  it("runs an explicitly lite chat turn on Flash-Lite with minimal thinking", async () => {
    stubFetch(() => textResponse("hello"));
    await runGeminiChat({
      system: "s",
      history: [{ role: "user", content: "hi" }],
      model: GEMINI_LITE_MODEL,
    });
    expect(calls[0].model).toBe(GEMINI_LITE_MODEL);
    expect(thinkingLevelOf(calls[0])).toBe("minimal");
  });

  it("runs structured output on Flash-Lite with minimal thinking", async () => {
    stubFetch(() => textResponse(JSON.stringify({ ok: true })));
    const parsed = await runGeminiJson<{ ok: boolean }>({
      system: "s",
      prompt: "p",
      schema: { type: "object", properties: { ok: { type: "boolean" } } },
    });
    expect(parsed).toEqual({ ok: true });
    expect(calls[0].model).toBe(GEMINI_LITE_MODEL);
    expect(thinkingLevelOf(calls[0])).toBe("minimal");
  });

  it("honors a Flash override on structured output", async () => {
    stubFetch(() => textResponse(JSON.stringify({ ok: true })));
    await runGeminiJson({
      system: "s",
      prompt: "p",
      schema: { type: "object" },
      model: GEMINI_MODEL,
    });
    expect(calls[0].model).toBe(GEMINI_MODEL);
    expect(thinkingLevelOf(calls[0])).toBe("low");
  });

  // Thinking knobs drift across model generations, so a 400 naming them must
  // degrade to default thinking rather than failing the turn.
  it("retries without thinkingConfig when the API rejects it", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => {});
    stubFetch((call) =>
      thinkingLevelOf(call)
        ? new Response("thinkingLevel is not supported", { status: 400 })
        : textResponse("recovered"),
    );
    await expect(runGeminiChat({ system: "s", history: [{ role: "user", content: "hi" }] }))
      .resolves.toBe("recovered");
    expect(calls).toHaveLength(2);
    expect(calls[1].model).toBe(GEMINI_MODEL);
  });

  it("names the model in API errors", async () => {
    stubFetch(() => new Response("boom", { status: 500 }));
    await expect(
      runGeminiJson({ system: "s", prompt: "p", schema: { type: "object" } }),
    ).rejects.toThrow(GEMINI_LITE_MODEL);
  });
});

describe("tool loop", () => {
  it("echoes model turns back verbatim so thoughtSignatures survive", async () => {
    const modelTurn = {
      role: "model",
      parts: [
        { text: "thinking", thought: true, thoughtSignature: "sig-1" },
        { functionCall: { name: "view_quest_log", args: {} }, thoughtSignature: "sig-2" },
      ],
    };
    let turn = 0;
    stubFetch(() =>
      turn++ === 0
        ? new Response(JSON.stringify({ candidates: [{ content: modelTurn }] }), { status: 200 })
        : textResponse("all done"),
    );

    const run = vi.fn(async () => "3 quests remaining");
    const text = await runGeminiChat({
      system: "s",
      history: [{ role: "user", content: "how many quests?" }],
      tools: [{ name: "view_quest_log", description: "d", run }],
    });

    expect(text).toBe("all done");
    expect(run).toHaveBeenCalledOnce();
    const followup = calls[1].body as unknown as { contents: typeof modelTurn[] };
    expect(followup.contents[1]).toEqual(modelTurn);
    expect(followup.contents[2]).toEqual({
      role: "user",
      parts: [
        { functionResponse: { name: "view_quest_log", response: { result: "3 quests remaining" } } },
      ],
    });
  });

  it("omits parameters for tools with an empty schema", async () => {
    stubFetch(() => textResponse("done"));
    await runGeminiChat({
      system: "s",
      history: [{ role: "user", content: "hi" }],
      tools: [
        { name: "no_args", description: "d", input_schema: { properties: {} }, run: async () => "" },
        {
          name: "with_args",
          description: "d",
          input_schema: { properties: { q: { type: "string" } } },
          run: async () => "",
        },
      ],
    });
    const body = calls[0].body as unknown as {
      tools: { functionDeclarations: { name: string; parameters?: unknown }[] }[];
    };
    const [noArgs, withArgs] = body.tools[0].functionDeclarations;
    expect(noArgs.parameters).toBeUndefined();
    expect(withArgs.parameters).toEqual({ properties: { q: { type: "string" } } });
  });
});
