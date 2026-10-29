"use client";

import { useEffect, useState } from "react";
import { Icon } from "./Icon";

const STORAGE_KEY = "theme";
const DARK_QUERY = "(prefers-color-scheme: dark)";

/** The stored choice, or null when following the system preference. */
function storedChoice(): "light" | "dark" | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : null;
  } catch {
    return null;
  }
}

function apply(dark: boolean) {
  document.documentElement.classList.toggle("dark", dark);
}

/**
 * Light/dark switch. Until it is flipped, the theme follows the system preference;
 * the root layout applies the stored choice before first paint.
 */
export function ThemeToggle() {
  // Null until mounted: the current theme is only known in the browser.
  const [dark, setDark] = useState<boolean | null>(null);

  useEffect(() => {
    const media = matchMedia(DARK_QUERY);
    const sync = () => {
      if (storedChoice() === null) {
        apply(media.matches);
        setDark(media.matches);
      }
    };
    media.addEventListener("change", sync);
    const frame = requestAnimationFrame(() => setDark(document.documentElement.classList.contains("dark")));
    return () => {
      media.removeEventListener("change", sync);
      cancelAnimationFrame(frame);
    };
  }, []);

  function toggle() {
    const next = !dark;
    try {
      localStorage.setItem(STORAGE_KEY, next ? "dark" : "light");
    } catch {
      // Storage unavailable: the choice lasts for this page only.
    }
    apply(next);
    setDark(next);
  }

  return (
    <button
      type="button"
      role="switch"
      aria-checked={dark ?? false}
      aria-label="Dark mode"
      title={dark ? "Switch to light mode" : "Switch to dark mode"}
      onClick={toggle}
      className="relative flex h-7 w-13 shrink-0 items-center rounded-full border border-zinc-200 bg-zinc-100 p-0.5 transition-colors hover:border-zinc-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-violet-500 aria-checked:border-zinc-700 aria-checked:bg-zinc-800 dark:hover:border-zinc-600"
    >
      <Icon name="sun" className="absolute left-1.5 size-3.5 text-amber-500" />
      <Icon name="moon" className="absolute right-1.5 size-3.5 text-zinc-400" />
      <span
        aria-hidden
        className={`relative z-10 grid size-5.5 place-items-center rounded-full shadow-sm ring-1 ${
          dark === null ? "invisible" : "transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]"
        } ${dark ? "translate-x-6 bg-zinc-950 ring-white/10" : "translate-x-0 bg-white ring-zinc-900/5"}`}
      >
        <Icon
          key={dark ? "moon" : "sun"}
          name={dark ? "moon" : "sun"}
          className={`size-3.5 animate-scale-in ${dark ? "text-violet-300" : "text-amber-500"}`}
        />
      </span>
    </button>
  );
}
