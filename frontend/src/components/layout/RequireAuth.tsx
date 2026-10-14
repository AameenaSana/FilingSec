"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { useAuth } from "@/lib/auth/AuthProvider";

/**
 * Renders children only for signed-in users and sends everyone else to the
 * login page. The backend still authorizes every API call; this only governs
 * what the UI shows.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { state } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (state.status === "anonymous") {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [state.status, router, pathname]);

  if (state.status !== "authenticated") {
    return (
      <p role="status" className="p-8 text-sm text-zinc-500">
        Loading…
      </p>
    );
  }
  return children;
}
