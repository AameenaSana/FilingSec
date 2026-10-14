import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiFetch, readCookie } from "./client";

function jsonResponse(body: unknown, status = 200, contentType = "application/json") {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } });
}

describe("readCookie", () => {
  it("finds a cookie by exact name", () => {
    expect(readCookie("XSRF-TOKEN", "a=1; XSRF-TOKEN=abc%3D; b=2")).toBe("abc%3D");
    expect(readCookie("TOKEN", "XSRF-TOKEN=abc")).toBeUndefined();
  });
});

describe("apiFetch", () => {
  afterEach(() => {
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });

  it("calls the same-origin API and parses JSON", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ name: "FilingLens" }));

    await expect(apiFetch("/system/info")).resolves.toEqual({ name: "FilingLens" });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/v1/system/info");
    expect(init?.credentials).toBe("same-origin");
  });

  it("sends the CSRF token on state-changing requests only", async () => {
    document.cookie = "XSRF-TOKEN=tok%2Ben";
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => jsonResponse({}));

    await apiFetch("/watchlist", { method: "POST", body: JSON.stringify({ cik: "0000320193" }) });
    await apiFetch("/watchlist");

    const postHeaders = new Headers(fetchMock.mock.calls[0][1]?.headers);
    const getHeaders = new Headers(fetchMock.mock.calls[1][1]?.headers);
    expect(postHeaders.get("X-XSRF-TOKEN")).toBe("tok+en");
    expect(postHeaders.get("Content-Type")).toBe("application/json");
    expect(getHeaders.has("X-XSRF-TOKEN")).toBe(false);
  });

  it("throws ApiError carrying the ProblemDetail", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(
        { type: "urn:filinglens:problem:not-found", status: 404, detail: "Filing 'x' was not found", requestId: "r1" },
        404,
        "application/problem+json",
      ),
    );

    const error = await apiFetch("/filings/x").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect((error as ApiError).code).toBe("not-found");
    expect((error as ApiError).message).toBe("Filing 'x' was not found");
    expect((error as ApiError).problem.requestId).toBe("r1");
  });

  it("handles non-JSON error bodies", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("<html>Bad Gateway</html>", { status: 502, statusText: "Bad Gateway" }),
    );

    const error = (await apiFetch("/system/info").catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(502);
    expect(error.message).toBe("Bad Gateway");
  });
});
