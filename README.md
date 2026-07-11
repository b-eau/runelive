# OSRS Sidekick

Your Old School RuneScape companion: a modern PWA dashboard + AI guide, fed by a
lightweight RuneLite sync plugin.

```
┌─────────────────┐   events    ┌──────────────────────┐
│ RuneLite plugin │ ──────────▶ │  Next.js web app     │
│  (plugin/)      │  /api/ingest│  (web/)              │
│  skills, quests,│             │  • event log +       │
│  bank, KC …     │ ◀────────── │    materialized state│
└─────────────────┘  link flow  │  • dashboard PWA     │
                                │  • AI chat + voice   │
                                └─────────┬────────────┘
                                          │ Prisma
                                       Postgres
                                    (Neon in prod)
```

## Quick start (local)

```bash
cd web
npm install
cp .env.example .env          # point DATABASE_URL at a local Postgres
npx prisma migrate dev        # applies migrations + runs the seed
npm run dev                   # http://localhost:3000
```

Sign in with **beaumitch@gmail.com** — the magic link prints to the terminal
running `npm run dev` (no email service needed in dev). The seed includes the
demo account **dummymitch** (stats mirrored from the real `beaumitch` via the
Wise Old Man API) with a main-game profile, a leagues profile, 18 months of
XP/bank/KC history, quests, diaries, bank, and goals.

**No account needed to try it**: the landing page has a guest mode — enter any
OSRS username and Sidekick looks it up (Wise Old Man, falling back to the
official hiscores), renders a stats view with level/99 progress, and opens a
limited chat seeded with personalized starter questions.

Optional env in `web/.env`:

- `ANTHROPIC_API_KEY` — enables the real Sidekick AI chat + voice assistant
  (otherwise chat runs in demo mode).
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` — enables sign-in-with-Google.
- `RESEND_API_KEY` — sends real magic-link emails.

## The plugin

```bash
cd plugin
./gradlew run   # dev RuneLite client with the plugin loaded
```

In RuneLite: enable **OSRS Sidekick Sync** in plugin settings (it's opt-in)
and tick **Link account** in the same settings panel — a browser opens, you
sign in, and the plugin starts syncing. It sends:

- **Skills** — on login, on level-up, and every 15 min when total XP changed
- **Quests** — full quest log on login
- **Bank / inventory / equipment** — when the container changes (bank opens, gear swaps)
- **Boss kill counts** — parsed from kill-count chat messages
- Profile kind (main / leagues / deadman) and account type (ironman etc.) ride
  along with every batch

## Testing

```bash
cd web
npm test          # vitest unit suite (throwaway Postgres db)
npm run test:e2e  # Playwright end-to-end (builds + boots a seeded server)
```

E2E runs are hermetic: guest lookups use a fixture (`GUEST_FIXTURES=1`), chat
runs in keyless demo mode, and magic links are echoed through a test-only seam
(`E2E_AUTH_LINK=1`). Both suites run in CI on every PR touching `web/`.

## Docs

- [ARCHITECTURE.md](ARCHITECTURE.md) — data model, ingestion pipeline, scaling
- [DEPLOYMENT.md](DEPLOYMENT.md) — deploying with managed providers (Vercel + Neon/Supabase)

## Repository layout

| Path | What |
|---|---|
| `web/` | Next.js 15 PWA: dashboard, auth, ingestion API, AI chat/voice |
| `plugin/` | RuneLite plugin: account linking + event sync |
