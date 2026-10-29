import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeToggle } from "./ThemeToggle";

function stubSystemTheme(dark: boolean) {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: dark,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
}

describe("ThemeToggle", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove("dark");
    stubSystemTheme(false);
  });

  it("switches between light and dark and remembers the choice", async () => {
    render(<ThemeToggle />);
    const toggle = screen.getByRole("switch", { name: "Dark mode" });
    expect(toggle).toHaveAttribute("aria-checked", "false");

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-checked", "true");
    expect(document.documentElement).toHaveClass("dark");
    expect(localStorage.getItem("theme")).toBe("dark");

    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-checked", "false");
    expect(document.documentElement).not.toHaveClass("dark");
    expect(localStorage.getItem("theme")).toBe("light");
  });

  it("starts from the theme applied before first paint", async () => {
    // The root layout's script has already chosen dark from the system preference.
    document.documentElement.classList.add("dark");
    render(<ThemeToggle />);

    expect(await screen.findByRole("switch", { name: "Dark mode", checked: true })).toBeInTheDocument();
  });
});
