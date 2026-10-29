"use client";

import { useEffect, useState } from "react";
import { Icon, type IconName } from "./Icon";

type Theme = "light" | "dark" | "system";

const ORDER: Theme[] = ["light", "dark", "system"];
const ICONS: Record<Theme, IconName> = { light: "sun", dark: "moon", system: "monitor" };
const STORAGE_KEY = "theme";

function readTheme(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "system";
  } catch {
    return "system";
  }
}

function apply(theme: Theme) {
  const dark = theme === "dark" || (theme === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
}

/** Cycles light → dark → system. The root layout applies the stored choice before first paint. */
export function ThemeToggle() {
  // Null until mounted: the stored choice is only readable in the browser.
  const [theme, setTheme] = useState<Theme | null>(null);

  useEffect(() => {
    const media = matchMedia("(prefers-color-scheme: dark)");
    const sync = () => readTheme() === "system" && apply("system");
    media.addEventListener("change", sync);
    const frame = requestAnimationFrame(() => setTheme(readTheme()));
    return () => {
      media.removeEventListener("change", sync);
      cancelAnimationFrame(frame);
    };
  }, []);

  function cycle() {
    const next = ORDER[(ORDER.indexOf(theme ?? "system") + 1) % ORDER.length];
    try {
      if (next === "system") {
        localStorage.removeItem(STORAGE_KEY);
      } else {
        localStorage.setItem(STORAGE_KEY, next);
      }
    } catch {
      // Storage unavailable: the choice lasts for this page only.
    }
    apply(next);
    setTheme(next);
  }

  const current = theme ?? "system";
  return (
    <button
      type="button"
      onClick={cycle}
      aria-label={`Theme: ${current}. Switch theme`}
      title={`Theme: ${current}`}
      className="rounded-md p-1.5 text-zinc-600 transition hover:bg-zinc-100 hover:text-zinc-900 active:scale-90 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
    >
      <Icon key={current} name={ICONS[current]} className="size-5 animate-scale-in" />
    </button>
  );
}
