# OSRS Sidekick

An interactive AI chat agent that helps you navigate the vast, complex world of **Old School RuneScape** — your "genius best friend" who knows your account inside-out and gives specific, well-researched, personalised advice.

The killer feature is **personalization**: the agent is fed a rich snapshot of your account and reasons over *your* levels, kill-counts, account type and progress to recommend the most efficient path for *you* — pointing out exactly which requirements you already meet, which you're missing, and how to close the gap.

This repository is the **first stage**: before integrating with the live RuneLite client, it talks to the public OSRS community APIs to build a useful subset of the experience, and ships the chat as a **web UI that supports both voice and text**, with the agent's prompt tuned per modality. The code is structured so the reusable core can be lifted into a RuneLite plugin later with minimal change.

> **Status:** standalone Java web harness. It does **not** depend on `net.runelite:client`. All API interaction is unit-tested, cached and throttled.

---

## What it does today

- **Personalised context** from [WiseOldMan](https://wiseoldman.net): skills, boss kill-counts, minigame/clue scores, account type (ironman rulesets included), build, efficiency (EHP/EHB), account age and recent activity — injected into the model's system prompt.
- **Agentic tools** the model can call to research before advising:
  - `get_grand_exchange_price` — live GE prices from the [OSRS Wiki real-time prices API](https://prices.runescape.wiki) so cost figures are real, not guessed.
  - `search_osrs_wiki` — the OSRS Wiki, to verify quest/diary/strategy requirements instead of relying on memory.
- **Voice and text chat** in the browser:
  - **Text mode** — replies can use light formatting, exact numbers, and links.
  - **Voice mode** — speech-to-text in, replies spoken aloud (Web Speech API), and the prompt is re-tuned for the ear: short, conversational, no markdown/URLs, numbers spoken naturally.
- **Good API citizenship** — every upstream call is rate-limited (token bucket) and cached (per-RSN / per-item / per-query) so a chatty conversation never bursts the community services.

### Pluggable LLM providers

The chat model sits behind a single `LlmClient` seam and a provider-neutral message model, so adding a backend is just a new `LlmClient` implementation plus an `LlmProvider` enum value — nothing else changes. Two providers ship today:

- **Anthropic** (`claude-opus-4-8` by default) — Messages API.
- **Google Gemini** (`gemini-3.5-flash` by default; `gemini-3.1-flash-lite` for cheap smoke tests) — `generateContent`, including function calling and Gemini 3 thought-signature round-tripping.

Both are called over raw HTTP (OkHttp + Gson) so the agent core stays portable into a RuneLite plugin (see *Design notes*). Select with `SIDEKICK_PROVIDER`.

---

## Running it

Requirements: JDK 11+ (the build targets Java 11 bytecode), and an API key for your chosen provider.

```bash
# Anthropic (default provider)
export ANTHROPIC_API_KEY=sk-ant-...

# …or Google Gemini
export SIDEKICK_PROVIDER=gemini
export GEMINI_API_KEY=AIza...

export SIDEKICK_PLAYER="Your RSN"        # optional default username
export SIDEKICK_PORT=8080                # optional (default 8080)
./gradlew run
```

Then open <http://localhost:8080>, enter your OSRS username, pick **Text** or **Voice**, and start chatting.

### Configuration (environment variables / system properties)

| Variable | Default | Purpose |
|---|---|---|
| `SIDEKICK_PROVIDER` | `anthropic` | `anthropic` or `gemini` |
| `ANTHROPIC_API_KEY` | — | Anthropic key (required when provider is anthropic) |
| `GEMINI_API_KEY` | — | Gemini key (required when provider is gemini; `GOOGLE_API_KEY` also accepted) |
| `SIDEKICK_MODEL` | `claude-opus-4-8` / `gemini-3.5-flash` | Model id (default depends on provider) |
| `SIDEKICK_PLAYER` | — | Default RSN if the UI leaves it blank |
| `SIDEKICK_PORT` | `8080` | Web UI port |
| `SIDEKICK_THINKING` | `true` | Adaptive thinking on/off (Anthropic) |
| `SIDEKICK_ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Override (proxies/tests) |
| `SIDEKICK_GEMINI_BASE_URL` | `https://generativelanguage.googleapis.com` | Override (proxies/tests) |
| `SIDEKICK_WOM_BASE_URL` | `https://api.wiseoldman.net/v2` | Override |
| `SIDEKICK_PRICES_BASE_URL` | `https://prices.runescape.wiki/api/v1/osrs` | Override |
| `SIDEKICK_WIKI_BASE_URL` | `https://oldschool.runescape.wiki` | Override |

> ⚠️ Your RSN is sent to the third-party community APIs to personalise advice. In the future plugin, the equivalent toggle must be **opt-in** and carry the standard IP-disclosure warning.

---

## Testing

```bash
./gradlew test
```

The 46 tests cover: the TTL cache (caching, expiry, single-flight, failure-not-cached) and token-bucket rate limiter with a controllable clock; each API client against a `MockWebServer` (parsing, not-found handling, and that caching/throttling actually de-duplicate calls); both LLM clients' request building and response parsing (Anthropic tool-use; Gemini function-calling incl. schema sanitization and thought-signature replay); the agentic tool loop (dispatch, neutral assistant-turn reconstruction, error handling, step limit, voice-vs-text prompts); and a **full end-to-end test** driving the running HTTP server through a complete tool-using turn with mocked LLM + community APIs.

Normal `./gradlew test` never touches a live API or spends tokens — everything is mocked or faked. A separate live test against real Gemini + real community APIs is gated behind `GEMINI_API_KEY` (skipped otherwise):

```bash
export GEMINI_API_KEY=AIza...
export SIDEKICK_MODEL=gemini-3.1-flash-lite   # or gemini-3.5-flash for high fidelity
./gradlew test --tests 'com.runelive.sidekick.GeminiLiveIntegrationTest' --rerun
```

---

## Architecture

Single Gradle module; packages are split along the line that matters for the future plugin port — reusable core vs. platform glue.

```
com.runelive.sidekick
├── cache/            TtlCache (single-flight, TTL) + RateLimiter (token bucket), Clock-driven
├── http/             OkHttp+Gson helpers (User-Agent, rate-limit, JSON)
├── context/          PlayerContext model + PlayerContextSource (the portability seam)
│   ├── wiseoldman/   WiseOldManClient  → PlayerContext
│   ├── prices/       PriceClient       → GE prices
│   └── wiki/         WikiClient        → article summaries
├── llm/              LlmClient (interface) + AnthropicClient + GeminiClient + neutral content model
├── agent/            AgentService (tool loop) + SystemPrompts (modality-tuned) + tools/
├── web/              ChatService + WebServer (embedded JDK HTTP server) + DTOs   ← platform glue
└── Sidekick / SidekickApp   composition root + entry point
src/main/resources/web/   index.html, app.js, styles.css   ← the voice+text SPA   ← platform glue
```

### How this becomes a RuneLite plugin

The split above is deliberate. To restructure into a plugin:

1. **Swap the context source.** `PlayerContextSource` is the seam. Replace `CloudPlayerContextSource` (community APIs) with a `ClientPlayerContextSource` backed by the live client — which can fill in the rest of the vision the cloud APIs can't: **bank contents, current location, quest log, achievement diaries, daily playtime**. Every consumer of `PlayerContext` stays unchanged.
2. **Drop the `web/` package and the SPA.** Replace them with a RuneLite `PluginPanel` (and/or overlay) that calls `ChatService`/`AgentService` directly.
3. **Inject instead of construct.** `Sidekick` is plain constructor injection today; in the plugin, `@Inject` the shared `OkHttpClient` and `Gson` and move the wiring into `startUp()`. The `llm`, `agent`, `cache`, `http` and `context` packages move over essentially verbatim.
4. **Config → `@ConfigItem`.** `SidekickConfig` maps onto a `@ConfigGroup("osrs-sidekick")` config interface (third-party-server toggles opt-in + IP warning).

`context/`, `cache/`, `http/`, `llm/`, and `agent/` are the portable core; `web/` and the SPA are the only throwaway parts.

### Design notes

- **Raw HTTP for the LLM providers (not the official SDKs).** The agent core is destined for a RuneLite hub plugin, which must reuse the injected `OkHttpClient`/`Gson` and avoid heavy transitive dependencies. Thin clients over OkHttp keep the core portable and trivial to mock — so the same code runs in the harness and (later) the plugin.
- **Provider-neutral message model.** The agent speaks in `TextPart`/`ToolUsePart`/`ToolResultPart`; each `LlmClient` translates to its own wire format (Anthropic content blocks, Gemini `contents`/`functionCall`). Adding a provider touches only its client + the `LlmProvider` enum.
- **No DI container.** Plain constructor injection keeps the dependency footprint tiny and maps 1:1 onto `@Inject` in the plugin.
- **Threading.** Upstream calls run on background/web-server threads (never a client thread); the rate limiter parks rather than `Thread.sleep`. When ported, the synchronous OkHttp `execute()` calls should become `enqueue()` with `clientThread.invoke()` callbacks.
