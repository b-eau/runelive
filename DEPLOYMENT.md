# Deploying OSRS Sidekick

The web app is a standard Next.js 15 + Prisma application. The recommended
managed stack is **Vercel (app) + Neon (Postgres) + Resend (email)**, but any
Node host + managed Postgres works the same way (Railway, Render, Fly.io,
Supabase).

## 1. Database — Neon (or Supabase/RDS)

1. Create a Postgres database at [neon.tech](https://neon.tech) (free tier is
   plenty for ~1k users) and copy the connection string. The Prisma schema
   and the committed migrations already target Postgres — no schema changes
   needed.
2. Seed the demo data (optional in prod): `DATABASE_URL="…" npm run db:seed`

> **Pooling:** on serverless (Vercel), use Neon's pooled connection string
> (`-pooler` host) for `DATABASE_URL`.

## 2. Web app — Vercel

1. Import the repo in Vercel, set **Root Directory = `web/`** and framework
   preset **Next.js**.
2. Build command: leave the default. `web/package.json` has a `vercel-build`
   script (`prisma generate && prisma migrate deploy && next build`) that
   Vercel picks up automatically — it generates the Prisma client and applies
   pending migrations on every deploy. If you previously set a custom build
   command in the dashboard, clear it (or set it to `npm run vercel-build`).
3. Environment variables:

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | Neon pooled connection string (`-pooler` host) — used by the app at runtime |
   | `DIRECT_DATABASE_URL` | Neon direct connection string (same URL without `-pooler`) — used by `prisma migrate deploy` at build time, which does not work through the pooler |
   | `APP_URL` | `https://your-domain.com` |
   | `AUTH_SECRET` | `openssl rand -hex 32` |
   | `RESEND_API_KEY` | from [resend.com](https://resend.com) (magic-link email) |
   | `EMAIL_FROM` | `OSRS Sidekick <sidekick@your-domain.com>` (verified in Resend) |
   | `ANTHROPIC_API_KEY` | from [console.anthropic.com](https://console.anthropic.com) — powers chat + voice |
   | `GEMINI_API_KEY` | alternative to `ANTHROPIC_API_KEY`, from [aistudio.google.com](https://aistudio.google.com) (Anthropic wins if both are set); `GEMINI_MODEL` optionally overrides the default `gemini-3.5-flash` |
   | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | optional, see below |

4. **Deployment Protection:** Vercel protects deployment URLs behind Vercel
   SSO by default (visitors get redirected to a Vercel login). To make the
   site public, go to Project → Settings → Deployment Protection and disable
   Vercel Authentication (or scope it to previews only).

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
