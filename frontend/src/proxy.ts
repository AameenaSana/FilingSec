import { NextResponse, type NextRequest } from "next/server";

/** Spring Session's cookie name. */
export const SESSION_COOKIE = "SESSION";

/**
 * Optimistic route guard: requests for app pages without a session cookie are
 * redirected to the login page before rendering. Whether the session is valid
 * is decided by the backend on every API call; RequireAuth handles expired
 * sessions on the client.
 */
export function proxy(request: NextRequest) {
  if (request.cookies.has(SESSION_COOKIE)) {
    return NextResponse.next();
  }
  const login = new URL("/login", request.url);
  login.searchParams.set("next", request.nextUrl.pathname + request.nextUrl.search);
  return NextResponse.redirect(login);
}

export const config = {
  matcher: ["/dashboard/:path*", "/companies/:path*", "/watchlist/:path*", "/filings/:path*", "/compare/:path*", "/settings/:path*"],
};
