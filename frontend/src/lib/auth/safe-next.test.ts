import { describe, expect, it } from "vitest";
import { safeNextPath, tokenFromHash } from "./safe-next";

describe("safeNextPath", () => {
  it.each([
    ["/settings", "/settings"],
    ["/companies/0000320193?tab=filings", "/companies/0000320193?tab=filings"],
    [null, "/dashboard"],
    ["", "/dashboard"],
    ["//evil.example", "/dashboard"],
    ["/\\evil.example", "/dashboard"],
    ["https://evil.example", "/dashboard"],
    ["javascript:alert(1)", "/dashboard"],
  ])("%s -> %s", (input, expected) => {
    expect(safeNextPath(input)).toBe(expected);
  });
});

describe("tokenFromHash", () => {
  it("reads the token from a fragment", () => {
    expect(tokenFromHash("#token=abc_-123")).toBe("abc_-123");
    expect(tokenFromHash("")).toBeNull();
    expect(tokenFromHash("#other=1")).toBeNull();
  });
});
