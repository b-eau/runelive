import { expect, test } from "@playwright/test";

test.describe("landing page", () => {
  test("renders the hero and feature cards", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("understood");
    await expect(page.getByText("Every trend, tracked")).toBeVisible();
    await expect(page.getByTestId("try-form")).toBeVisible();
  });

  test("username form routes into the guest preview", async ({ page }) => {
    await page.goto("/");
    const form = page.getByTestId("try-form");
    await form.getByRole("textbox").fill("dummymitch");
    await form.getByRole("button", { name: /try it free/i }).click();
    await expect(page).toHaveURL(/\/try\/dummymitch/);
    await expect(page.getByTestId("guest-dashboard")).toBeVisible({ timeout: 15_000 });
  });
});
