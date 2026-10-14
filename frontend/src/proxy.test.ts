import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { proxy } from "./proxy";

describe("proxy", () => {
  it("redirects to login with the original path when there is no session cookie", () => {
    const response = proxy(new NextRequest("http://localhost:3000/settings?tab=security"));

    expect(response.status).toBe(307);
    const location = new URL(response.headers.get("location")!);
    expect(location.pathname).toBe("/login");
    expect(location.searchParams.get("next")).toBe("/settings?tab=security");
  });

  it("lets requests with a session cookie through", () => {
    const request = new NextRequest("http://localhost:3000/dashboard", { headers: { cookie: "SESSION=abc" } });

    const response = proxy(request);

    expect(response.headers.get("location")).toBeNull();
    expect(response.headers.get("x-middleware-next")).toBe("1");
  });
});
