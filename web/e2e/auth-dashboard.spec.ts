import { expect, test } from "@playwright/test";

// Full sign-in + dashboard flow against the seeded database. The magic link
// is fetched through the E2E_AUTH_LINK seam (the response echoes the link the
// email would have contained).

async function signIn(page: import("@playwright/test").Page, email: string) {
  const res = await page.request.post("/api/auth/magic", { data: { email } });
  expect(res.ok()).toBeTruthy();
  const { devLink } = (await res.json()) as { devLink: string };
  expect(devLink).toContain("/api/auth/callback?token=");
  await page.goto(devLink);
}

/** Opens the seeded main-game profile and waits for the navigation to land. */
async function openMainProfile(page: import("@playwright/test").Page) {
  await page.getByText("Main game").click();
  await expect(page).toHaveURL(/\/p\//);
}

test.describe("authenticated dashboard", () => {
  test("magic link signs in and shows the seeded profiles", async ({ page }) => {
    await signIn(page, "beaumitch@gmail.com");
    await expect(page).toHaveURL(/\/dashboard/);
    await expect(page.getByRole("heading", { name: "Your characters" })).toBeVisible();
    // Seed: one account (dummymitch) with a main-game and a leagues profile.
    await expect(page.getByText("Main game")).toBeVisible();
    await expect(page.getByText("Leagues", { exact: true })).toBeVisible();
  });

  test("profile overview renders seeded stats and trends", async ({ page }) => {
    await signIn(page, "beaumitch@gmail.com");
    await openMainProfile(page);

    await expect(page.getByText("Total level")).toBeVisible();
    await expect(page.getByText("1,904")).toBeVisible();
    await expect(page.getByText("Bank value", { exact: true })).toBeVisible();
    await expect(page.getByText("Account goals")).toBeVisible();
    // Trend charts rendered (SVG line charts)
    await expect(page.locator("svg[role='img']").first()).toBeVisible();
  });

  test("skills tab shows the 99 and per-skill chart", async ({ page }) => {
    await signIn(page, "beaumitch@gmail.com");
    await openMainProfile(page);
    await page.getByRole("link", { name: "Skills" }).click();

    // Slayer is the seeded 99
    const slayerCell = page.locator(".skill-cell", { hasText: "Slayer" });
    await expect(slayerCell).toContainText("99");
    await slayerCell.click();
    await expect(page.getByText("Level 99")).toBeVisible();
  });

  test("sidekick chat page loads in demo mode", async ({ page }) => {
    await signIn(page, "beaumitch@gmail.com");
    await openMainProfile(page);
    // Exact name: /Sidekick/ alone would also match the "OSRS Sidekick" brand link.
    await page.getByRole("link", { name: "Sidekick ✨" }).click();

    await expect(page.getByText("Demo mode", { exact: false })).toBeVisible();
    await expect(page.getByText(/ask me anything/i)).toBeVisible();
  });

  test("a brand-new user sees the empty state", async ({ page }) => {
    await signIn(page, `fresh-${Date.now()}@example.com`);
    await expect(page).toHaveURL(/\/dashboard/);
    await expect(page.getByText("No accounts linked yet")).toBeVisible();
  });

  test("linking by username imports a hiscores snapshot", async ({ page }) => {
    await signIn(page, `rsn-link-${Date.now()}@example.com`);
    // The guest "sign up" CTA lands here with the username prefilled
    // (fixture lookup: deterministic, no network).
    const username = `rsn ${Date.now() % 100_000_000}`;
    await page.goto(`/link?username=${encodeURIComponent(username)}`);
    await expect(page.getByPlaceholder("Your OSRS username")).toHaveValue(username);

    await page.getByRole("button", { name: "Link by username" }).click();
    await expect(page).toHaveURL(/\/p\//, { timeout: 15_000 });

    // Snapshot materialized: hiscores-only banner plus real stats.
    await expect(page.getByText("Linked by username", { exact: false })).toBeVisible();
    await expect(page.getByText("Total level")).toBeVisible();
  });
});
