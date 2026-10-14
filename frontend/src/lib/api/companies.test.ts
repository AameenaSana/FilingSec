import { describe, expect, it } from "vitest";
import { formatFiscalYearEnd } from "./companies";

describe("formatFiscalYearEnd", () => {
  it.each([
    ["0927", "Sep 27"],
    ["1231", "Dec 31"],
    ["0630", "Jun 30"],
    [null, null],
    ["1331", null],
    ["abc", null],
  ])("%s -> %s", (input, expected) => {
    expect(formatFiscalYearEnd(input)).toBe(expected);
  });
});
