# OSRS Sidekick — Engineering Handoff

_Last updated: 2026-07-10. Branch: `claude/osrs-sidekick-web-app-215qts`._

This document is the onboarding brief for the engineer taking over. It covers
the product vision, exactly what exists and is verified today, and the
remaining work split into features, testing, and productionization — with
fallbacks where there is real uncertainty.

## 1. Vision (the spec, condensed)

OSRS Sidekick is a standalone web application + a lightweight RuneLite plugin:

- **Web app**: sleek responsive PWA. Sign-in via magic link and Google.
  Managed relational DB stores per-user → per-OSRS-account → per-profile
  (main/leagues/deadman) state: stats, bank/inventory/equipment, quests,
  diaries, boss KC, account type. Polished raw-data UI (Wise Old Man-style:
  trends for XP / bank value / KC, quest log, searchable bank) **plus** an LLM
  assistant (chat + realtime voice) that knows the account context, is steered
  by user-stated goals ("untrim slayer cape", "quest cape as AFK as possible"),
  and has tools (search bank, view quest log, view diaries, KC, XP gains).
- **Plugin**: pure sync client. Sends events when information becomes
  available (interface opens, login) or values change (level-up, 15-min XP
  heartbeat). Account linking must be one-click easy (browser sign-in flow).
- **Architecture**: append-only event ingestion that scales to ~1k users × 20
  h/week × 2 years, with materialized current-state + daily rollups for fast
  reads and range analytics (e.g. mining XP/day 2023–2025).

## 2. What exists today (all committed on this branch)

### Repo layout

```
web/       Next.js 15 (App Router, TS) + Prisma. The entire backend + PWA.
plugin/    RuneLite plugin (Gradle, Java 11 target). Compiles clean.
ARCHITECTURE.md  Data model, ingestion pipeline, scaling math, growth path.
DEPLOYMENT.md    Vercel + Neon + Resend + Google OAuth instructions.
README.md        Quick start for both halves.
```

### Web app (`web/`) — feature-complete for v1

- **Schema** (`prisma/schema.prisma`): User → OsrsAccount (by RuneLite
  `accountHash`) → Profile (unique per `[accountId, kind]`). Append-only
  `Event` log with unique `dedupeKey`; materialized state tables
  (`SkillState`, `QuestState`, `DiaryState`, `ContainerState`,
  `KillCountState`); daily rollups (`XpSample`, `BankValueSample`,
  `KcSample`); `Goal`, `ChatMessage`, `ItemPrice`, plus auth tables
  (`Session`, `MagicLink`, `LinkCode`, `ApiToken` — all tokens stored
  **hashed**). Deliberately portable: **no Prisma enums, no JSON columns**
  (payloads are JSON strings) so SQLite (dev) ↔ Postgres (prod) is a one-line
  provider switch. BigInt for XP/gp columns.
- **Auth** (`src/lib/auth.ts` + `src/app/api/auth/*`): custom, no NextAuth
  dependency. Magic links (console transport in dev — link prints to server
  stdout; Resend when `RESEND_API_KEY` set) + Google OAuth (auto-hidden unless
  env vars set) + httpOnly session cookies (30-day, hashed tokens in DB).
- **Plugin link flow** (`src/app/api/link/*`, `/link` page): plugin POSTs
  `link/start` → gets 8-char human code + `pollSecret` + browser URL; user
  signs in and claims; plugin polls and receives the bearer token exactly once
  (then it is scrubbed from `LinkCode`).
- **Ingestion** (`src/app/api/ingest`, `src/lib/materialize.ts`): batched
  events, ≤200/batch, bearer-token auth, idempotent via `dedupeKey` unique
  index. Materialization runs inline today; `ingestEvents()` is the seam to
  move behind a queue later (interface already isolates it).
- **Analytics** (`src/app/api/profiles/[id]/analytics`): metric = `skill_xp` |
  `bank_value` | `boss_kc`, optional from/to range, day/week/month buckets,
  value + delta per bucket.
- **Dashboard UI** (`src/app/p/[profileId]/*`): Overview (stat tiles, total-XP
  + bank-value trend charts, goals editor with server actions, weekly gains),
  Skills (grid with level-progress bars + per-skill XP chart with 30d/90d/1y
  ranges), Quests (log + diary matrix), Bank (search, bank/equipped/inventory
  tabs, values), Bosses (KC table + per-boss trend). Profile switcher across
  accounts/profiles. Custom SVG `TrendChart` with crosshair + tooltip (built
  per the dataviz skill: validated palette, single-series, no legend). Dark +
  light theme, hand-rolled CSS design system in `globals.css`. PWA manifest +
  minimal service worker in `public/`.
- **AI Sidekick** (`src/lib/sidekick.ts`, `src/app/api/chat`, `/p/…/chat`):
  Anthropic SDK 0.110, `client.beta.messages.toolRunner` with
  `claude-opus-4-8`, adaptive thinking, `max_iterations: 8`. Account summary +
  active goals injected in the system prompt; profile-scoped tools:
  `search_bank`, `view_quest_log`, `view_achievement_diaries`,
  `view_boss_kill_counts`, `view_xp_gains`. Tools close over the authorized
  profileId — the model cannot read other users' rows. Conversation persisted
  per profile in `ChatMessage` (last 24 turns replayed). **Demo mode** when
  `ANTHROPIC_API_KEY` unset (returns the context it would reason over, so
  local demos work keyless). **Voice**: browser Web Speech API — STT feeds the
  same chat endpoint, replies spoken via speechSynthesis, continuous
  listen→answer→listen loop, mic button in the chat page.
- **Seed** (`prisma/seed.ts`): user `beaumitch@gmail.com` owning account
  **dummymitch** with a STANDARD profile mirroring the real `beaumitch` from
  the Wise Old Man API (snapshot JSONs vendored in `prisma/seed-data/`), a
  synthetic LEAGUES ironman profile (demonstrates multi-profile), 550 days of
  deterministic (seeded PRNG) XP/bank/KC history blended with real WOM
  snapshots, 168 quests, diaries, a priced bank, goals, and a **stable dev
  ingest token**: `dev-ingest-token-dummymitch`.

### Plugin (`plugin/`) — feature-complete for v1, not yet game-tested

`com.osrssidekick`: `SidekickSyncPlugin` (event collection + batching + 10s
flush + 15-min heartbeat), `SidekickApiClient` (OkHttp async, injected client
+ Gson per RuneLite rules), `SidekickPanel` (link button, status, open
dashboard), `SidekickSyncConfig` (opt-in `syncEnabled` with the required
third-party-server warning; `backendUrl` default `http://localhost:3000`).
Syncs: skills (login/level-up/heartbeat), quest log (login, via `Quest`
enum on client thread), bank/inventory/equipment (on `ItemContainerChanged`,
hash-deduped, 30s rate limit for non-bank), boss KC (chat regex), profile
kind from `WorldType`, account type from `VarbitID.IRONMAN`. Per-account
tokens stored in RuneLite config (`token.<accountHash>`). Compiles clean
against `latest.release` (all gameval constants verified by compilation).
Follows AGENTS.md threading rules (no client-thread IO, executor shut down
with `shutdownNow`, futures cancelled).

### Verified end-to-end (production build, `next start`)

- `next build` passes with zero type errors; all routes render.
- Magic-link flow: request → link printed → callback → session cookie →
  dashboard 200.
- Full link handshake via curl acting as the plugin: start → claim (authed) →
  poll returns token once.
- Ingest: 3-event batch (SKILLS + KILL_COUNT + BANK) accepted, profile lazily
  created with `IRONMAN` type; **replaying the identical batch ingests 0**
  (idempotency proven).
- Analytics: `?metric=skill_xp&skill=mining&granularity=month&from=2025-01-01`
  returns correct month buckets with deltas.
- All six profile pages return 200 authed; chat endpoint returns demo-mode
  reply containing real seeded context.
- Plugin: `gradle build -x test` green.

### NOT yet verified (be honest about this)

- The plugin has **never run against a real game client** (per AGENTS.md this
  requires a human with a Jagex account: `cd plugin && ./gradlew run`).
  Compile-time constants are right, but runtime behaviors to confirm in-game:
  quest states a few ticks after login, container IDs firing as expected,
  KC regex against real messages, the link browser-open flow.
- Chat with a real `ANTHROPIC_API_KEY` (tool-runner path is per-docs but
  hasn't executed a live LLM turn).
- Voice mode in a real browser (Web Speech API needs mic permission; unit
  logic is straightforward but untested).
- Google OAuth (needs real client credentials; code path mirrors the tested
  magic-link path).
- Postgres. Dev runs SQLite. The schema is portable by construction, but the
  provider switch + `migrate dev` regeneration (DEPLOYMENT.md §1) hasn't been
  executed against a live Neon/Supabase instance.

## 3. Remaining feature work (prioritized)

1. **Diary syncing in the plugin.** The whole pipeline (API `DIARIES` event
   type, `DiaryState`, UI) already works — the seed proves it. The plugin
   skips diaries because achievement-diary completion varbits need a curated
   map. Approach: build an area×tier → varbit map from
   `net.runelite.api.gameval.VarbitID` (search for `*_DIARY_*`/`DIARY`
   constants, or crib the mapping from RuneLite's built-in
   `achievementdiary` plugin), read on login, emit a `DIARIES` event.
   Fallback: sync when the diary interface opens (`WidgetLoaded`) — less
   elegant, still spec-compliant ("sync when interface opens").
2. **Live GE prices.** `ItemPrice` is seeded statically (~60 items). Add
   `web/scripts/sync-prices.ts` pulling
   `https://prices.runescape.wiki/api/v1/osrs/mapping` (names) +
   `/latest` (prices) into `ItemPrice`, run on a cron (Vercel Cron route or
   GitHub Action). Set a descriptive `User-Agent` — the wiki asks for one.
   Note items synced from the plugin that lack an `ItemPrice` row currently
   display as `Item #id` and value 0; the price sync fixes both.
3. **Proactive guidance.** Spec asks for proactive prompts, only conversational
   exists. Cheapest good version: a "Sidekick suggests" card on the Overview
   page — a daily (cached in DB) LLM call summarizing goal-relevant next steps
   from the same context builder (`buildContext()` is reusable as-is). Add
   web-push later if wanted (PWA already has a service worker to hang it on).
4. **Chat streaming.** `/api/chat` is request/response; fine for short
   answers, but streaming (SSE from the tool runner's `stream: true` mode)
   would feel better on long answers. The voice loop actually *prefers*
   non-streamed (it speaks whole replies), so keep the JSON path for voice.
5. **Voice upgrade (optional).** Web Speech API is free and vendor-less but
   Chrome-quality-dependent. If product wants studio-quality voice, swap the
   browser TTS for a streamed TTS API and keep everything else — the voice
   loop is isolated inside `ChatPanel.tsx` (`startVoice`/`send(spoken=true)`).
6. **Account management UI.** Revoke ApiTokens, unlink accounts, delete
   account (GDPR-ish). Tables already support it (`ApiToken.revokedAt`);
   ingest already rejects revoked tokens. Just needs a settings page.
7. **Collection log / achievements** (stretch): new event type + state table,
   same materializer pattern. The plugin can read collection log on interface
   open.
8. **XP-to-goal projections** (stretch): "days to 99 at current pace" — pure
   read off `XpSample`, render on skill page.

## 4. Remaining testing & verification

1. **In-game plugin session** (human required, ~20 min): `cd plugin &&
   ./gradlew run`, log into a Jagex account (see RuneLite wiki "Using Jagex
   Accounts"), enable the plugin, link against `npm run dev`, then check:
   skills row appears on login; open bank → bank page populates; gain a level
   → LEVEL_UP + skills sync; kill any boss / check KC message parses;
   world-hop to a leagues/DMM world → second profile appears.
2. **Automated tests** (none exist yet — deliberate v1 tradeoff):
   - *Highest value first*: unit tests for `materialize.ts` (each event type,
     idempotency, overall-XP synthesis, day bucketing) and the analytics
     bucketing. Vitest + a throwaway SQLite file is enough; no mocking needed.
   - API integration tests: the exact curl choreography in §2 ("Verified")
     codified — auth → link → ingest → read back. Supertest against
     `next start` or route-handler unit calls.
   - Plugin: JUnit for the KC regex and payload builders (pure functions);
     RuneLite client itself can't be integration-tested headlessly.
   - E2E: Playwright (Chromium is preinstalled in this environment) for
     sign-in → dashboard → chat demo flow.
3. **Live-LLM smoke test**: set `ANTHROPIC_API_KEY`, ask bank/quest/gains
   questions, confirm tool calls hit and answers ground in seeded data.
4. **Postgres dress rehearsal**: run DEPLOYMENT.md §1 against a free Neon DB,
   run seed, click through. Watch for: BigInt behavior identical, `Bytes`
   none used, case-sensitivity of `contains` (SQLite is case-insensitive for
   ASCII by default; Postgres is not — bank search uses JS-side filtering so
   it's safe, but `mode: "insensitive"` may be wanted if queries move to SQL).
5. **Load sanity check** (optional): script 100 fake tokens × 20 events/min
   against `/api/ingest` for an hour; watch p95 and SQLite→Postgres deltas.

## 5. Productionization & maintainability

None of this exists yet; suggested order:

1. **CI (GitHub Actions)** — two workflows:
   - `web.yml`: on PR + main, paths `web/**`: `npm ci`, `prisma generate`,
     `next build`, `npm test` (once tests exist), optionally `prisma migrate
     diff` to catch drift.
   - `plugin.yml`: on PR + main, paths `plugin/**`: `gradle build` with Java
     11 toolchain (temurin), cache Gradle. This also acts as the RuneLite
     Plugin Hub pre-submission check.
   Branch protection on `master` requiring both.
2. **Deployments**: connect the repo to Vercel with Root Directory `web/` —
   this gives preview deployments per PR (status auto-posted on the PR) and
   production deploys on merge to `master` for free. Add
   `prisma migrate deploy` to the Vercel build command (DEPLOYMENT.md §2).
   Use a Neon **branch database** per preview if you want isolated preview
   data (Neon's Vercel integration automates this); otherwise previews can
   share a staging DB.
3. **Secrets/env hygiene**: `.env.example` is the contract; add a startup
   assertion that `AUTH_SECRET` is not the dev default when
   `NODE_ENV=production` (small guard in `auth.ts`).
4. **Lint/format**: add `eslint-config-next` + prettier and a
   `lint-staged`/husky pre-commit (or a plain CI lint job — CI-only avoids
   local-hook drift; team preference).
5. **Migrations discipline**: once Postgres is the source of truth,
   regenerate `prisma/migrations` for Postgres **once** (DEPLOYMENT.md) and
   from then on only additive `migrate dev` → `migrate deploy`. Never edit
   applied migrations.
6. **Observability**: Vercel gives request logs; add Sentry (or Vercel's
   error monitoring) for `/api/ingest` and `/api/chat` failures, and a
   `/api/health` route (DB ping) for uptime checks. Log-based alert on
   ingest 5xx rate.
7. **Rate limiting**: `/api/auth/magic` (email spam) and `/api/link/start`
   (code farming) should get simple per-IP limits — Upstash Ratelimit or
   Vercel Firewall rules; both are drop-in.
8. **Plugin distribution**: for real users, submit to the RuneLite Plugin Hub
   (repo must contain only the plugin at root — either split `plugin/` to its
   own repo at submission time, or keep a subtree mirror; the code already
   follows the hub rules: opt-in third-party-server warning, no reflection,
   Java 11, gameval constants, BSD-compatible license needed — **add a
   LICENSE file (BSD-2) before submission**, it's currently missing).
9. **Chat cost control**: per-user daily message cap (count `ChatMessage`
   rows) before calling the API; `max_iterations` is already bounded at 8.

## 6. Known uncertainties & fallbacks

| Risk | Current position | Fallback |
|---|---|---|
| Wise Old Man API is behind Cloudflare (plain curl gets challenged) | Seed data is vendored as JSON in `prisma/seed-data/`, so nothing at runtime depends on WOM | If you need to re-fetch, send a browser-like `User-Agent` (that worked) or use their documented API politely |
| Web Speech API quality/availability varies by browser | Feature-detected; UI degrades to text chat with an explanatory note | Swap to a hosted STT/TTS (e.g. OpenAI Realtime or ElevenLabs) inside `ChatPanel.tsx` only |
| `VarbitID.IRONMAN` value→type mapping (0–6) | Matches RuneLite's historical AccountType ordinal; compiles | Verify in-game once; mapping lives in one switch in `SidekickSyncPlugin.refreshIdentity()` |
| Anthropic tool-runner is a beta SDK surface | Pinned `@anthropic-ai/sdk` ^0.110; runner per current docs | If the beta surface shifts, the manual tool loop is a ~40-line drop-in replacement (docs in the claude-api skill; `runSidekick()` is the only call site) |
| SQLite dev vs Postgres prod drift | Schema deliberately avoids non-portable features | Postgres dress rehearsal (§4.4) before first real deploy |
| Vercel Hobby 60 s function cap vs chat turns | `maxDuration=120` set; needs Pro | Reduce `max_iterations`, or move `/api/chat` to a streaming response (starts sending immediately, avoiding the cap) |
| One profile per `[account, kind]` assumes e.g. one LEAGUES profile ever | True per league season only | If per-season profiles are wanted, widen `kind` to include a season tag (e.g. `LEAGUES_V`) — pure data change, no code change needed |

## 7. Operational crib sheet

```bash
# web dev loop
cd web && npm install && cp .env.example .env
npx prisma migrate dev            # migrate + seed (rerunnable: npm run db:reset)
npm run dev                       # sign in as beaumitch@gmail.com; link prints to console

# stable dev ingest token (seeded): dev-ingest-token-dummymitch
curl -X POST localhost:3000/api/ingest \
  -H "Authorization: Bearer dev-ingest-token-dummymitch" \
  -H "Content-Type: application/json" \
  -d '{"profileKind":"STANDARD","events":[{"type":"KILL_COUNT","occurredAt":"2026-07-10T12:00:00Z","payload":{"boss":"zulrah","kc":9}}]}'

# plugin
cd plugin && ./gradlew build      # CI check (use system gradle if wrapper download is blocked)
cd plugin && ./gradlew run        # dev RuneLite client (human, needs game account)
```
