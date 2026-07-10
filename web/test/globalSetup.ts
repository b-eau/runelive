// Runs once before the whole test run (separate from test-file workers):
// provisions a throwaway SQLite database at a fixed path and applies the
// Prisma schema to it. Test files point DATABASE_URL at the same file (see
// test/setup.ts) so no cross-process coordination is needed.

import { execSync } from "child_process";
import { existsSync, mkdirSync, rmSync } from "fs";
import path from "path";

export const TEST_DB_DIR = path.join(__dirname, ".tmp");
export const TEST_DB_PATH = path.join(TEST_DB_DIR, "vitest.db");
export const TEST_DATABASE_URL = `file:${TEST_DB_PATH}`;

export default function setup() {
  rmSync(TEST_DB_DIR, { recursive: true, force: true });
  mkdirSync(TEST_DB_DIR, { recursive: true });

  execSync("npx prisma db push --skip-generate --accept-data-loss", {
    cwd: path.join(__dirname, ".."),
    env: { ...process.env, DATABASE_URL: TEST_DATABASE_URL },
    stdio: "inherit",
  });

  if (!existsSync(TEST_DB_PATH)) {
    throw new Error("Test database was not created");
  }
}
