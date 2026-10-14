"use client";

import { useEffect, useRef, useState } from "react";
import { tokenFromHash } from "@/lib/auth/safe-next";

/**
 * Reads the single-use token from the URL fragment (#token=...) of an emailed
 * link, then removes it from the address bar and history.
 *
 * @returns undefined while reading, null if absent, otherwise the token
 */
export function useLinkToken(): string | null | undefined {
  const [token, setToken] = useState<string | null | undefined>(undefined);
  // Survives StrictMode's double effect run, after which the fragment is already gone.
  const read = useRef<string | null | undefined>(undefined);

  useEffect(() => {
    if (read.current === undefined) {
      read.current = tokenFromHash(window.location.hash);
      if (read.current) {
        window.history.replaceState(null, "", window.location.pathname + window.location.search);
      }
    }
    setToken(read.current);
  }, []);

  return token;
}
