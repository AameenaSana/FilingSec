"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { authApi, type Account } from "@/lib/api/auth";

type AuthState =
  | { status: "loading"; user: null }
  | { status: "authenticated"; user: Account }
  | { status: "anonymous"; user: null };

interface AuthContextValue {
  state: AuthState;
  /** Re-reads the session from the backend. */
  refresh: () => Promise<void>;
  /** Records a user returned by login or an account update. */
  setUser: (user: Account) => void;
  signOut: () => Promise<void>;
  /** Marks the session as gone without calling the backend (e.g. after account deletion). */
  clear: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading", user: null });

  const refresh = useCallback(() => loadSession().then(setState), []);

  useEffect(() => {
    let cancelled = false;
    loadSession().then((next) => !cancelled && setState(next));
    return () => {
      cancelled = true;
    };
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      refresh,
      setUser: (user) => setState({ status: "authenticated", user }),
      signOut: async () => {
        try {
          await authApi.logout();
        } finally {
          setState({ status: "anonymous", user: null });
        }
      },
      clear: () => setState({ status: "anonymous", user: null }),
    }),
    [state, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

async function loadSession(): Promise<AuthState> {
  try {
    return { status: "authenticated", user: await authApi.me() };
  } catch {
    // 401, or the backend is unreachable: either way there is no usable session.
    return { status: "anonymous", user: null };
  }
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside <AuthProvider>");
  }
  return context;
}
