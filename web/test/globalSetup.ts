// Runs once before the whole test run (separate from test-file workers):
// resets a throwaway Postgres database and applies the Prisma schema to it.
// Test files point DATABASE_URL at the same database (see test/setup.ts) so
// no cross-process coordination is needed.
//
// Point TEST_DATABASE_URL at any Postgres you have available — a local
// install, Docker, or a Neon branch. CI provides a service container.

import { execSync } from "child_process";
import path from "path";

export const TEST_DATABASE_URL =
  process.env.TEST_DATABASE_URL ??
  "postgresql://postgres:postgres@127.0.0.1:5432/sidekick_test";

export default function setup() {
  const cwd = path.join(__dirname, "..");
  const env = { ...process.env, DATABASE_URL: TEST_DATABASE_URL };
  // Recreate an empty schema, then apply the current Prisma schema to it.
  // (Two steps instead of `db push --force-reset`, which newer Prisma CLIs
  // refuse to run unattended.)
  execSync("npx prisma db execute --schema prisma/schema.prisma --stdin", {
    cwd,
    env,
    input: 'DROP SCHEMA IF EXISTS "public" CASCADE; CREATE SCHEMA "public";',
  });
  execSync("npx prisma db push --skip-generate", { cwd, env, stdio: "inherit" });
}
