"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { NotificationBell } from "@/components/notifications/NotificationBell";
import { Icon } from "@/components/ui/Icon";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { useAuth } from "@/lib/auth/AuthProvider";
import { CommandPalette } from "./CommandPalette";
import { NAV } from "./nav";

// Settings lives in the account menu on wide screens.
const PRIMARY_NAV = NAV.filter((item) => item.href !== "/settings");

/** Closes a popover on Escape or a click outside {@code ref}. */
function useDismiss(open: boolean, ref: RefObject<HTMLElement | null>, close: () => void) {
  useEffect(() => {
    if (!open) {
      return;
    }
    const onEvent = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === "Escape" : !ref.current?.contains(event.target as Node)) {
        close();
      }
    };
    document.addEventListener("mousedown", onEvent);
    document.addEventListener("keydown", onEvent);
    return () => {
      document.removeEventListener("mousedown", onEvent);
      document.removeEventListener("keydown", onEvent);
    };
  }, [open, ref, close]);
}

function initials(name: string) {
  return (
    name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]!.toUpperCase())
      .join("") || "?"
  );
}

export function AppHeader() {
  const { state, signOut } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);
  const account = useRef<HTMLDivElement>(null);
  const closeAccount = useCallback(() => setAccountOpen(false), []);
  useDismiss(accountOpen, account, closeAccount);

  // Close the mobile menu after navigating.
  const [lastPath, setLastPath] = useState(pathname);
  if (pathname !== lastPath) {
    setLastPath(pathname);
    setMenuOpen(false);
    setAccountOpen(false);
  }

  async function onSignOut() {
    await signOut();
    router.replace("/login");
  }

  const isCurrent = (href: string) => pathname.startsWith(href);
  const name = state.user?.displayName ?? "";

  return (
    <header className="sticky top-0 z-40 border-b border-zinc-200/80 bg-white/75 backdrop-blur-lg dark:border-zinc-800/80 dark:bg-zinc-950/75">
      <div className="mx-auto flex h-14 w-full max-w-6xl items-center gap-4 px-4 sm:px-6">
        <button
          type="button"
          aria-label={menuOpen ? "Close menu" : "Open menu"}
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((o) => !o)}
          className="-ml-1 rounded-md p-1.5 text-zinc-600 hover:bg-zinc-100 md:hidden dark:text-zinc-400 dark:hover:bg-zinc-800"
        >
          <Icon name={menuOpen ? "close" : "menu"} className="size-5" />
        </button>
        <Link href="/dashboard" className="group flex items-center gap-2 font-semibold tracking-tight">
          <span className="grid size-7 place-items-center rounded-lg bg-linear-to-br from-violet-500 to-indigo-600 text-white shadow-sm transition-transform group-hover:scale-105 group-hover:rotate-3">
            <Icon name="search" className="size-3.5" strokeWidth="2.5" />
          </span>
          FilingLens
        </Link>
        <nav aria-label="Main" className="hidden items-center gap-1 text-sm md:flex">
          {PRIMARY_NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              aria-current={isCurrent(item.href) ? "page" : undefined}
              className="flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-zinc-900 aria-[current=page]:bg-violet-50 aria-[current=page]:font-medium aria-[current=page]:text-violet-700 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100 dark:aria-[current=page]:bg-violet-500/15 dark:aria-[current=page]:text-violet-300"
            >
              <Icon name={item.icon} className="size-4 opacity-80" />
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-1.5 text-sm sm:gap-2">
          <CommandPalette />
          <ThemeToggle />
          {state.user && <NotificationBell />}
          <div ref={account} className="relative">
            <button
              type="button"
              aria-haspopup="menu"
              aria-expanded={accountOpen}
              aria-label="Account menu"
              onClick={() => setAccountOpen((o) => !o)}
              className="grid size-8 place-items-center rounded-full bg-linear-to-br from-zinc-700 to-zinc-900 text-xs font-semibold text-white ring-violet-500/40 transition hover:ring-4 dark:from-zinc-200 dark:to-zinc-400 dark:text-zinc-900"
            >
              {initials(name)}
            </button>
            {accountOpen && (
              <div
                role="menu"
                className="absolute right-0 z-20 mt-2 w-56 origin-top-right animate-scale-in overflow-hidden rounded-xl border border-zinc-200 bg-white p-1 shadow-lg dark:border-zinc-800 dark:bg-zinc-950"
              >
                <div className="px-3 py-2">
                  <p className="truncate font-medium">{name}</p>
                  {state.user?.email && <p className="truncate text-xs text-zinc-500">{state.user.email}</p>}
                </div>
                <div className="my-1 h-px bg-zinc-200 dark:bg-zinc-800" />
                <Link
                  role="menuitem"
                  href="/settings"
                  className="flex items-center gap-2 rounded-lg px-3 py-2 hover:bg-zinc-100 dark:hover:bg-zinc-800"
                >
                  <Icon name="settings" />
                  Settings
                </Link>
                <button
                  role="menuitem"
                  type="button"
                  onClick={onSignOut}
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-500/10"
                >
                  <Icon name="logout" />
                  Sign out
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
      {menuOpen && (
        <nav
          aria-label="Main mobile"
          className="animate-fade-in border-t border-zinc-200 px-4 py-3 md:hidden dark:border-zinc-800"
        >
          <ul className="grid grid-cols-2 gap-1">
            {NAV.map((item) => (
              <li key={item.href}>
                <Link
                  href={item.href}
                  aria-current={isCurrent(item.href) ? "page" : undefined}
                  className="flex items-center gap-2 rounded-lg px-3 py-2.5 text-sm text-zinc-700 hover:bg-zinc-100 aria-[current=page]:bg-violet-50 aria-[current=page]:font-medium aria-[current=page]:text-violet-700 dark:text-zinc-300 dark:hover:bg-zinc-800 dark:aria-[current=page]:bg-violet-500/15 dark:aria-[current=page]:text-violet-300"
                >
                  <Icon name={item.icon} />
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      )}
    </header>
  );
}
