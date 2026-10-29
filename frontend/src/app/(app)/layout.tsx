import { Suspense, type ReactNode } from "react";
import { AppHeader } from "@/components/layout/AppHeader";
import { RequireAuth } from "@/components/layout/RequireAuth";

// Pages here render only once the browser has confirmed the session (RequireAuth), so
// they cannot be part of a prerendered shell. Each page therefore sets
// `instant = false`; otherwise instant-navigation validation reports it as dropped.
// The backend still authorizes every request.

const loading = (
  <p role="status" className="animate-pulse p-8 text-sm text-zinc-500">
    Loading…
  </p>
);

export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    // The shell reads the current path, which on dynamic routes is only known at request time.
    <Suspense fallback={loading}>
      <RequireAuth>
        <AppHeader />
        <main className="mx-auto w-full max-w-6xl flex-1 animate-fade-in px-4 py-8 sm:px-6">{children}</main>
      </RequireAuth>
    </Suspense>
  );
}
