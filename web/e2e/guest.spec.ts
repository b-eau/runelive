import { expect, test } from "@playwright/test";

// The server runs with GUEST_FIXTURES=1: lookups return a deterministic
// snapshot (no external network) and chat runs in keyless demo mode.

test.describe("guest experience", () => {
  test("shows a rich stats view for a looked-up player", async ({ page }) => {
    await page.goto("/try/tester");
    const dashboard = page.getByTestId("guest-dashboard");
    await expect(dashboard).toBeVisible({ timeout: 15_000 });

    // Identity + headline stats from the fixture snapshot
    await expect(page.getByTestId("guest-name")).toHaveText("tester");
    await expect(page.getByText("Combat 119")).toBeVisible();
    await expect(dashboard.getByText("Total level")).toBeVisible();
    await expect(dashboard.getByText("1,904")).toBeVisible();

    // All 24 skills render with progress bars
    await expect(page.getByTestId("guest-skills").locator(".skill-cell")).toHaveCount(24);

    // Boss KCs and the closest-level-up teaser
    await expect(dashboard.getByText("Kraken", { exact: false }).first()).toBeVisible();
    await expect(dashboard.getByText("Almost there")).toBeVisible();

    // Sign-up funnel is present
    await expect(page.getByRole("link", { name: /create your free account/i })).toBeVisible();
  });

  test("suggested queries feed the guest chat and get a reply", async ({ page }) => {
    await page.goto("/try/tester");
    await expect(page.getByTestId("guest-dashboard")).toBeVisible({ timeout: 15_000 });

    const suggestions = page.getByTestId("guest-suggestions").getByRole("button");
    await expect(suggestions.first()).toBeVisible();
    const suggestionText = (await suggestions.first().textContent()) ?? "";
    await suggestions.first().click();

    // The suggestion becomes the user message; demo-mode Sidekick replies
    // with the snapshot context.
    await expect(page.locator(".msg.user")).toContainText(suggestionText.slice(0, 30));
    await expect(page.locator(".msg.assistant")).toContainText("Demo mode", { timeout: 15_000 });
    await expect(page.locator(".msg.assistant")).toContainText("combat level 119");
  });

  test("free-form chat round trip", async ({ page }) => {
    await page.goto("/try/tester");
    await expect(page.getByTestId("guest-chat")).toBeVisible({ timeout: 15_000 });

    await page.getByTestId("guest-chat-input").fill("What should I train next?");
    await page.getByTestId("guest-chat").getByRole("button", { name: "Send" }).click();

    await expect(page.locator(".msg.user")).toContainText("What should I train next?");
    await expect(page.locator(".msg.assistant")).toContainText("Total level 1904", { timeout: 15_000 });
  });

  test("invalid username shows the error state with a way back", async ({ page }) => {
    await page.goto("/try/%21%21bad%21%21"); // "!!bad!!"
    await expect(page.getByTestId("guest-error")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("link", { name: /try a different name/i })).toBeVisible();
  });
});
