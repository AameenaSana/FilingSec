import { expect, test } from "@playwright/test";

test.describe("access control and headers", () => {
  test("app pages require a session", async ({ page }) => {
    await page.goto("/dashboard");
    await expect(page).toHaveURL(/\/login\?next=%2Fdashboard/);
  });

  test("the API rejects anonymous requests with a problem detail", async ({ request }) => {
    const response = await request.get("/api/v1/dashboard");
    expect(response.status()).toBe(401);
    expect(response.headers()["content-type"]).toContain("application/problem+json");
  });

  test("pages are served with a content security policy and cannot be framed", async ({ request }) => {
    const response = await request.get("/login");
    expect(response.headers()["content-security-policy"]).toContain("frame-ancestors 'none'");
    expect(response.headers()["x-frame-options"]).toBe("DENY");
    expect(response.headers()["x-powered-by"]).toBeUndefined();
  });

  test("the content security policy does not block the app's own scripts", async ({ page }) => {
    const violations: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error" && /Content Security Policy/i.test(message.text())) {
        violations.push(message.text());
      }
    });
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "Sign in" })).toBeEnabled();
    expect(violations).toEqual([]);
  });

  test("state-changing requests need a CSRF token", async ({ request }) => {
    const response = await request.post("/api/v1/auth/login", {
      data: { email: "nobody@example.com", password: "irrelevant password" },
    });
    expect(response.status()).toBe(403);
  });
});
