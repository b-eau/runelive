#!/usr/bin/env bash
# Boots the app for Playwright: throwaway SQLite db, seeded, guest lookups in
# fixture mode (no external network), magic links echoed in the API response.
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p e2e/.tmp
export DATABASE_URL="file:$(pwd)/e2e/.tmp/e2e.db"
export GUEST_FIXTURES=1
export E2E_AUTH_LINK=1
export AUTH_SECRET="e2e-secret-not-for-production"
export APP_URL="http://localhost:3100"
# Determinism: chat must run in demo mode even if the shell has a key.
unset ANTHROPIC_API_KEY || true

rm -f e2e/.tmp/e2e.db
npx prisma db push --skip-generate --accept-data-loss > /dev/null
npx tsx prisma/seed.ts > /dev/null

# CI builds beforehand; locally, build on demand.
[ -f .next/BUILD_ID ] || npx next build

exec npx next start -p 3100
