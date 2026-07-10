import { defineConfig, devices } from "@playwright/test";
import fs from "fs";

// This dev environment pre-installs a Chromium that may not match our
// @playwright/test version; use it via executablePath when present. CI runs
// `npx playwright install chromium` and takes the default resolution.
const preinstalledChromium = "/opt/pw-browsers/chromium";
const executablePath =
  process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ??
  (fs.existsSync(preinstalledChromium) ? preinstalledChromium : undefined);

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  // Small suite sharing one server + SQLite db + in-memory rate limits:
  // run serially for determinism.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:3100",
    trace: "on-first-retry",
    ...(executablePath ? { launchOptions: { executablePath } } : {}),
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "bash e2e/start-server.sh",
    url: "http://localhost:3100/api/health",
    reuseExistingServer: !process.env.CI,
    timeout: 240_000, // may include a cold `next build` locally
  },
});
