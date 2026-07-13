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

  test("chat: parallel conversations, history, markdown replies", async ({ page }) => {
    await signIn(page, "beaumitch@gmail.com");
    await openMainProfile(page);
    await page.getByRole("link", { name: "Sidekick ✨" }).click();

    // First conversation (demo mode replies instantly) appears in the rail,
    // titled after the opening message.
    await page.getByPlaceholder("Ask your Sidekick anything…").fill("first topic question");
    await page.getByRole("button", { name: "Send" }).click();
    await expect(page.locator(".msg.assistant").last()).toContainText("Demo mode", { timeout: 15_000 });
    await expect(page.locator(".chat-rail .conv", { hasText: "first topic question" })).toBeVisible();

    // Assistant replies are markdown bubbles with a raw-markdown copy button.
    await expect(page.locator(".msg.assistant.markdown .copy-btn").first()).toBeAttached();

    // A parallel thread via "New chat".
    await page.getByRole("button", { name: "+ New chat" }).click();
    await page.getByPlaceholder("Ask your Sidekick anything…").fill("second topic question");
    await page.getByRole("button", { name: "Send" }).click();
    await expect(page.locator(".chat-rail .conv")).toHaveCount(2);

    // Revisiting the first conversation restores its history.
    await page.locator(".chat-rail .conv", { hasText: "first topic question" }).click();
    await expect(page.locator(".msg.user").first()).toContainText("first topic question");

    // Fresh conversation starters stay reachable mid-conversation.
    await page.getByRole("button", { name: "Show suggested conversation starters" }).click();
    await expect(page.getByText("Start a fresh conversation:")).toBeVisible();
  });

  test("goals can be edited and deleted", async ({ page }) => {
    await signIn(page, "beaumitch@gmail.com");
    await openMainProfile(page);

    // Edit the first active goal's title in place. The edit input has no
    // placeholder (the "add goal" input does), so scope to that.
    await page.getByRole("button", { name: "Edit goal" }).first().click();
    const titleInput = page.locator('input[name="title"]:not([placeholder])');
    await titleInput.fill("Edited goal title");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByText("Edited goal title")).toBeVisible();

    // Complete a goal, then delete it from the completed list.
    await page.getByRole("button", { name: "Mark complete" }).first().click();
    await page.getByRole("button", { name: "Delete goal" }).first().click();
    await expect(page.locator("s", { hasText: "Edited goal title" })).toHaveCount(0);
  });

  test("voice widget opens with idle controls", async ({ page }) => {
    await signIn(page, "beaumitch@gmail.com");
    await openMainProfile(page);
    await page.getByRole("button", { name: "Open voice Sidekick" }).click();
    await expect(page.getByText("Voice Sidekick")).toBeVisible();
    await expect(page.getByText("Tap the mic to talk")).toBeVisible();
    await expect(page.getByRole("button", { name: "Start talking" })).toBeVisible();
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
