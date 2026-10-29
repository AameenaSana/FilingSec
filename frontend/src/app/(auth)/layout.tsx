import Link from "next/link";
import type { ReactNode } from "react";
import { Icon } from "@/components/ui/Icon";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="relative isolate flex flex-1 flex-col items-center justify-center overflow-hidden px-4 py-16">
      <div
        aria-hidden
        className="pointer-events-none absolute -top-32 left-1/2 -z-10 h-96 w-[40rem] -translate-x-1/2 rounded-full bg-linear-to-tr from-violet-400/25 to-sky-300/25 blur-3xl dark:from-violet-600/20 dark:to-sky-600/15"
      />
      <Link href="/" className="group mb-8 flex items-center gap-2 text-xl font-semibold tracking-tight">
        <span className="grid size-8 place-items-center rounded-lg bg-linear-to-br from-violet-500 to-indigo-600 text-white shadow-sm transition-transform group-hover:scale-105 group-hover:rotate-3">
          <Icon name="search" className="size-4" strokeWidth="2.5" />
        </span>
        FilingLens
      </Link>
      <main className="w-full max-w-sm animate-fade-in-up rounded-2xl border border-zinc-200 bg-white/90 p-8 shadow-xl shadow-zinc-900/5 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/90">
        {children}
      </main>
    </div>
  );
}
