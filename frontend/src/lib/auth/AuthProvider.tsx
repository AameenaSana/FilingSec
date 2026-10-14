"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
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
  const queryClient = useQueryClient();
  const lastUserId = useRef<string | null>(null);

  const applySession = useCallback((next: AuthState) => {
    if (next.user) {
      lastUserId.current = next.user.id;
    }
    setState(next);
  }, []);

  const refresh = useCallback(() => loadSession().then(applySession), [applySession]);

  useEffect(() => {
    let cancelled = false;
    loadSession().then((next) => !cancelled && applySession(next));
    return () => {
      cancelled = true;
    };
  }, [applySession]);

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      refresh,
      setUser: (user) => {
        if (lastUserId.current !== null && lastUserId.current !== user.id) {
          queryClient.clear();
        }
        lastUserId.current = user.id;
        setState({ status: "authenticated", user });
      },
      signOut: async () => {
        try {
          await authApi.logout();
        } finally {
          // Never show one user's cached data to the next.
          queryClient.clear();
          setState({ status: "anonymous", user: null });
        }
      },
      clear: () => {
        queryClient.clear();
        setState({ status: "anonymous", user: null });
      },
    }),
    [state, refresh, queryClient],
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
