import { createClient, type SupabaseClient } from "@supabase/supabase-js";

/**
 * Supabase Auth is used only to sign in with an identity provider (Google). Once
 * the backend has accepted the Supabase access token, FilingLens uses its own
 * session cookie and the Supabase session is discarded.
 *
 * Both values are public and inlined at build time; without them the
 * "Continue with Google" button is not shown.
 */
const url = process.env.NEXT_PUBLIC_SUPABASE_URL ?? "";
const publishableKey = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY ?? "";

export const supabaseConfigured = url !== "" && publishableKey !== "";

/** Remembers where to go after the provider redirects back, across the round trip. */
const NEXT_KEY = "filinglens.oauth-next";

let client: SupabaseClient | null = null;

export function supabase(): SupabaseClient {
  if (!supabaseConfigured) {
    throw new Error("Supabase sign-in is not configured");
  }
  // PKCE: the provider redirect carries a one-time code, never tokens in the URL.
  // The callback page exchanges the code itself.
  client ??= createClient(url, publishableKey, {
    auth: { flowType: "pkce", detectSessionInUrl: false, autoRefreshToken: false, persistSession: true },
  });
  return client;
}

export function rememberNext(next: string) {
  try {
    sessionStorage.setItem(NEXT_KEY, next);
  } catch {
    // Storage unavailable: fall back to the default destination.
  }
}

export function takeNext(): string | null {
  try {
    const next = sessionStorage.getItem(NEXT_KEY);
    sessionStorage.removeItem(NEXT_KEY);
    return next;
  } catch {
    return null;
  }
}
