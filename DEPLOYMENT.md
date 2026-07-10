# Deploying OSRS Sidekick

The web app is a standard Next.js 15 + Prisma application. The recommended
managed stack is **Vercel (app) + Neon (Postgres) + Resend (email)**, but any
Node host + managed Postgres works the same way (Railway, Render, Fly.io,
Supabase).

## 1. Database — Neon (or Supabase/RDS)

1. Create a Postgres database at [neon.tech](https://neon.tech) (free tier is
   plenty for ~1k users) and copy the connection string.
2. Switch Prisma to Postgres — edit `web/prisma/schema.prisma`:

   ```prisma
   datasource db {
     provider = "postgresql"
     url      = env("DATABASE_URL")
   }
   ```

3. Regenerate the migrations for Postgres (one-time, from `web/`):

   ```bash
   rm -rf prisma/migrations
   DATABASE_URL="postgres://…" npx prisma migrate dev --name init
   ```

   The schema is written to be portable (no enums, no JSON columns), so no
   model changes are needed.

4. Seed the demo data (optional in prod): `DATABASE_URL="…" npm run db:seed`

> **Pooling:** on serverless (Vercel), use Neon's pooled connection string
> (`-pooler` host) for `DATABASE_URL`.

## 2. Web app — Vercel

1. Import the repo in Vercel, set **Root Directory = `web/`**.
2. Build command: `prisma generate && next build` (Vercel's default Next.js
   preset + a `postinstall: prisma generate` script also works).
3. Environment variables:

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | Neon pooled connection string |
   | `APP_URL` | `https://your-domain.com` |
   | `AUTH_SECRET` | `openssl rand -hex 32` |
   | `RESEND_API_KEY` | from [resend.com](https://resend.com) (magic-link email) |
   | `EMAIL_FROM` | `OSRS Sidekick <sidekick@your-domain.com>` (verified in Resend) |
   | `ANTHROPIC_API_KEY` | from [console.anthropic.com](https://console.anthropic.com) — powers chat + voice |
   | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | optional, see below |

4. Run migrations on deploy: add `prisma migrate deploy` to the build command,
   or run it once manually: `DATABASE_URL="…" npx prisma migrate deploy`.

### Google sign-in (optional)

1. [Google Cloud Console](https://console.cloud.google.com) → APIs & Services →
   Credentials → **Create OAuth client ID** (Web application).
2. Authorized redirect URI: `https://your-domain.com/api/auth/google/callback`.
3. Set `GOOGLE_CLIENT_ID` + `GOOGLE_CLIENT_SECRET`. The Google button appears
   automatically when both are present.

### Notes

- **Chat timeouts:** `/api/chat` sets `maxDuration = 120`; on Vercel this needs
  the Pro plan (Hobby caps at 60 s). Reducing `max_iterations` in
  `web/src/lib/sidekick.ts` keeps turns under 60 s if you stay on Hobby.
- **PWA:** the manifest + service worker are served from `web/public/`; no
  extra config. Users can "Add to Home Screen" on iOS/Android.
- **GE prices:** the seed installs a static price list. For live bank
  valuations, refresh the `ItemPrice` table from the OSRS wiki API
  (`https://prices.runescape.wiki/api/v1/osrs/latest` + `/mapping`) with a cron
  job (Vercel Cron hitting a small route, or a scheduled worker).

## 3. Point the plugin at production

In RuneLite → OSRS Sidekick Sync settings → **Sidekick server URL** →
`https://your-domain.com`. Linking and syncing work identically to local dev.

## 4. Scaling later

See [ARCHITECTURE.md](ARCHITECTURE.md) — the growth path is: queue the
materializer, partition the event log by month, and (only if needed) stream
events into a columnar store. None of these change the plugin or the API
contract.
