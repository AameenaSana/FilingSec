import { expect, type APIRequestContext, type Page } from "@playwright/test";

/** Mailpit's HTTP API (development mail catcher). */
const MAILPIT_URL = process.env.MAILPIT_URL ?? "http://localhost:8025";

export interface TestUser {
  name: string;
  email: string;
  password: string;
}

export function newUser(): TestUser {
  const id = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  return { name: "E2E Tester", email: `e2e-${id}@example.com`, password: "correct horse battery staple 42" };
}

/** Waits for the newest email to {@code to} and returns the first link to {@code path} in it. */
export async function emailLink(request: APIRequestContext, to: string, path: string): Promise<string> {
  let link: string | undefined;
  await expect
    .poll(
      async () => {
        const search = await request.get(`${MAILPIT_URL}/api/v1/search`, { params: { query: `to:"${to}"` } });
        const messages = (await search.json()).messages ?? [];
        for (const summary of messages) {
          const message = await (await request.get(`${MAILPIT_URL}/api/v1/message/${summary.ID}`)).json();
          const match = (message.Text as string).match(new RegExp(`https?://\\S+${path}\\S*`));
          if (match) {
            link = match[0];
            return true;
          }
        }
        return false;
      },
      { message: `email to ${to} with a ${path} link`, timeout: 30_000 },
    )
    .toBe(true);
  return link!;
}

/** Registers through the UI, confirms the email address and signs in. */
export async function signUp(page: Page, user: TestUser = newUser()): Promise<TestUser> {
  await page.goto("/register");
  await page.getByLabel("Name").fill(user.name);
  await page.getByLabel("Email").fill(user.email);
  await page.getByLabel("Password", { exact: true }).fill(user.password);
  await page.getByLabel("Confirm password").fill(user.password);
  await page.getByRole("button", { name: "Create account" }).click();

  // The link carries the token in the URL fragment, so it opens the frontend directly.
  const link = await emailLink(page.request, user.email, "/verify-email");
  await page.goto(link);
  await expect(page.getByText("Your email address is confirmed.")).toBeVisible();

  await signIn(page, user);
  return user;
}

export async function signIn(page: Page, user: TestUser) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(user.email);
  await page.getByLabel("Password").fill(user.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}
