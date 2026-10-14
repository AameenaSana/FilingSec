import { Suspense, type ReactNode } from "react";
import { AppHeader } from "@/components/layout/AppHeader";
import { RequireAuth } from "@/components/layout/RequireAuth";

const loading = (
  <p role="status" className="p-8 text-sm text-zinc-500">
    Loading…
  </p>
);

export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    // The shell reads the current path, which on dynamic routes is only known at request time.
    <Suspense fallback={loading}>
      <RequireAuth>
        <AppHeader />
        <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">{children}</main>
      </RequireAuth>
    </Suspense>
  );
}
