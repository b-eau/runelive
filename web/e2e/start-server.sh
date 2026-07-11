#!/usr/bin/env bash
# Boots the app for Playwright: throwaway Postgres db, seeded, guest lookups
# in fixture mode (no external network), magic links echoed in the API
# response. Point E2E_DATABASE_URL at any Postgres you have available — a
# local install, Docker, or a Neon branch. CI provides a service container.
set -euo pipefail
cd "$(dirname "$0")/.."

export DATABASE_URL="${E2E_DATABASE_URL:-postgresql://postgres:postgres@127.0.0.1:5432/sidekick_e2e}"
export GUEST_FIXTURES=1
export E2E_AUTH_LINK=1
export AUTH_SECRET="e2e-secret-not-for-production"
export APP_URL="http://localhost:3100"
# Determinism: chat must run in demo mode even if the shell has a key.
unset ANTHROPIC_API_KEY GEMINI_API_KEY || true

npx prisma db push --skip-generate --accept-data-loss --force-reset > /dev/null
npx tsx prisma/seed.ts > /dev/null

# CI builds beforehand; locally, build on demand.
[ -f .next/BUILD_ID ] || npx next build

exec npx next start -p 3100
