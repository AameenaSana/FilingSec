/**
 * Validates a post-login redirect target. Only same-origin absolute paths are
 * allowed, preventing open redirects such as "//evil.example" or "https://...".
 */
export function safeNextPath(next: string | null | undefined, fallback = "/dashboard"): string {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return fallback;
  }
  return next;
}

/** Reads `token` from a URL fragment such as "#token=abc". */
export function tokenFromHash(hash: string): string | null {
  const params = new URLSearchParams(hash.startsWith("#") ? hash.slice(1) : hash);
  return params.get("token");
}
