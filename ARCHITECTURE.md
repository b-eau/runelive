# OSRS Sidekick — Architecture

## Goals

- Ingest heterogeneous account-update events from a game plugin for years
  without schema churn.
- Serve the dashboard (current stats, quest log, bank, trends) with cheap
  indexed reads — never by replaying history.
- Support online analytical queries ("mining XP per day for 2023–2025")
  without a separate warehouse at the current scale.
- One user → many OSRS accounts → many profiles per account (main game,
  leagues, deadman), because game modes have fully independent progress.

## Data model

```
User ──▶ OsrsAccount (accountHash, displayName) ──▶ Profile (kind, accountType)
                        │                                │
                     ApiToken (plugin auth)              ├── Event            (append-only log)
                                                         ├── SkillState       ┐
                                                         ├── QuestState       │ materialized
                                                         ├── DiaryState       │ "current state"
                                                         ├── ContainerState   │ (1 row per key)
                                                         ├── KillCountState   ┘
                                                         ├── XpSample         ┐
                                                         ├── BankValueSample  │ daily rollups
                                                         └── KcSample         ┘ (1 row per day)
```

**Identity.** `accountHash` is RuneLite's stable per-game-account ID, so display
name changes don't fork history. `Profile.kind` comes from the world type at
sync time (DEADMAN world → the DEADMAN profile), so a leagues login can never
pollute main-game stats. Profiles are lazily upserted on first ingest.

## Ingestion pipeline

1. **Plugin batches.** Events accumulate client-side and flush every ~10 s as
   one `POST /api/ingest` (≤150 events, bearer token per account). Sync
   triggers are event-driven, not polling: container snapshots when the
   container changes, quest log when it becomes available (login), skills on
   level-up plus a 15-minute heartbeat only if total XP moved. A quiet AFK
   session generates almost no traffic.
2. **Append.** Each event lands in the immutable `Event` log
   (`type`, JSON payload, `occurredAt`, unique `dedupeKey`). Retried batches
   are idempotent — duplicate dedupeKeys are skipped, so the plugin can retry
   on any network failure without double-counting.
3. **Materialize.** The same transaction path upserts:
   - *Current state* tables keyed by `(profileId, entity)` — e.g. one row per
     skill, per quest, per container. Dashboard reads are single indexed
     lookups regardless of history size.
   - *Daily rollups* keyed by `(profileId, entity, utcDay)` with
     last-write-wins semantics — the row holds the day's closing value.
     Trends and analytics are linear scans over ≤365 rows/year/entity.

Analytical queries diff consecutive rollup rows: XP/day for a range is one
index-range read over `XpSample`, bucketed to day/week/month in
`/api/profiles/:id/analytics`.

## Does it scale? (napkin math)

Target: 1,000 users × 20 h play/week × 2 years.

- **Event volume.** A busy hour generates roughly: 4 skill syncs + a few
  container snapshots + a handful of KC events ≈ 20 events/hour. 1k users ×
  20 h/wk × 20 ≈ **400k events/week ⇒ ~42M events over 2 years**. Comfortably
  Postgres territory; the log is insert-only with one unique index.
- **Rollups stay small.** XpSample worst case = 25 skills × 365 days × 2 yr ×
  1k users ≈ 18M tiny rows, all reads hitting the composite PK. Charts read
  ≤730 rows.
- **Hot path isolation.** Reads never touch `Event`; it exists for audit,
  backfill, and re-materialization (if a materializer bug ships, replay the
  log). At larger scale, time-partition `Event` by month and archive cold
  partitions to object storage.

**Growth path** (not needed at 1k users, no interface changes required):

1. Move `ingestEvents` behind a queue (SQS/Cloud Tasks/pg-boss) — the API
   handler then only appends + enqueues, and materialization becomes an async
   worker. The function signature already isolates this.
2. Partition `Event` by `occurredAt` month; BRIN index on time.
3. If analytics outgrow rollups, stream the event log into DuckDB/ClickHouse —
   the JSON payloads carry everything needed to rebuild any projection.

## Auth

- **Web:** magic-link email (console transport in dev, Resend in prod) and
  optional Google OAuth. Sessions are random 256-bit tokens stored **hashed**
  in the DB, delivered as httpOnly cookies.
- **Plugin:** the link flow mints a per-account bearer token (also stored
  hashed). Plugin → `POST /api/link/start` → user signs in in the browser and
  claims the 8-char code → plugin polls with a private `pollSecret` and
  receives the token exactly once. Tokens are revocable per account.

## AI layer

Chat and voice share one backend (`/api/chat`): an Anthropic tool-use loop
(claude-opus-4-8, adaptive thinking) with a compact account summary + the
user's stated goals injected up-front, and profile-scoped tools
(`search_bank`, `view_quest_log`, `view_achievement_diaries`,
`view_boss_kill_counts`, `view_xp_gains`) for anything deeper. Tools close
over the authorized `profileId`, so the model can only ever read the
signed-in user's own rows. Voice mode is browser-native (Web Speech API STT →
same chat endpoint → speechSynthesis TTS), which keeps the realtime loop
vendor-free; the context + tools make it "aware of key context upfront with
tools available to query other information."
