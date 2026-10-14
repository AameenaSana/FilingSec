"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth/AuthProvider";

const NAV = [
  { href: "/dashboard", label: "Dashboard" },
  { href: "/companies", label: "Companies" },
  { href: "/watchlist", label: "Watchlist" },
  { href: "/research", label: "Research" },
  { href: "/settings", label: "Settings" },
];

export function AppHeader() {
  const { state, signOut } = useAuth();
  const pathname = usePathname();
  const router = useRouter();

  async function onSignOut() {
    await signOut();
    router.replace("/login");
  }

  return (
    <header className="border-b border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-950">
      <div className="mx-auto flex h-14 w-full max-w-6xl items-center gap-6 px-6">
        <Link href="/dashboard" className="font-semibold tracking-tight">
          FilingLens
        </Link>
        <nav aria-label="Main" className="flex gap-4 text-sm">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              aria-current={pathname.startsWith(item.href) ? "page" : undefined}
              className="text-zinc-600 hover:text-zinc-900 aria-[current=page]:font-medium aria-[current=page]:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100 dark:aria-[current=page]:text-zinc-100"
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-4 text-sm">
          {state.user && <span className="text-zinc-600 dark:text-zinc-400">{state.user.displayName}</span>}
          <button type="button" onClick={onSignOut} className="font-medium underline-offset-4 hover:underline">
            Sign out
          </button>
        </div>
      </div>
    </header>
  );
}
