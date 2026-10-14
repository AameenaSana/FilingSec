/**
 * Thin fetch wrapper for the FilingLens backend. All calls go to the same
 * origin (/api/v1/...), which Next.js proxies to the backend.
 *
 * - Sends cookies (session) with every request.
 * - Echoes the XSRF-TOKEN cookie as the X-XSRF-TOKEN header on state-changing
 *   requests, fetching a token first if none exists, as required by the
 *   backend's CSRF protection.
 * - Converts RFC 9457 ProblemDetail error responses into {@link ApiError}.
 */

export const API_BASE = "/api/v1";
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  requestId?: string;
  errors?: { field: string; message: string }[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }

  /** Stable machine-readable code, e.g. "not-found" from "urn:filinglens:problem:not-found". */
  get code(): string | undefined {
    return this.problem.type?.split(":").pop();
  }

  /** Validation messages keyed by field name. */
  get fieldErrors(): Record<string, string> {
    return Object.fromEntries((this.problem.errors ?? []).map((e) => [e.field, e.message]));
  }
}

export function readCookie(name: string, cookieString: string = document.cookie): string | undefined {
  return cookieString
    .split("; ")
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

export async function ensureCsrfToken(forceRefresh = false): Promise<string | undefined> {
  if (forceRefresh || !readCookie(CSRF_COOKIE)) {
    await fetch(`${API_BASE}/auth/csrf`, { credentials: "same-origin" });
  }
  const token = readCookie(CSRF_COOKIE);
  return token ? decodeURIComponent(token) : undefined;
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const mutating = !SAFE_METHODS.has(method);

  const send = async (refreshCsrf: boolean) => {
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    if (init.body !== undefined && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
    if (mutating) {
      const token = await ensureCsrfToken(refreshCsrf);
      if (token) {
        headers.set(CSRF_HEADER, token);
      }
    }
    return fetch(`${API_BASE}${path}`, { ...init, method, headers, credentials: "same-origin" });
  };

  let response = await send(false);
  if (!response.ok && mutating && response.status === 403) {
    const problem = await parseProblem(response.clone());
    // A stale CSRF token yields a generic 403; refresh the token and retry once.
    if (problem.type === "urn:filinglens:problem:forbidden") {
      response = await send(true);
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, await parseProblem(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export async function parseProblem(response: Response): Promise<ProblemDetail> {
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return { status: response.status, title: response.statusText };
  }
}

/** A user-presentable message for any error thrown by an API call. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return "Something went wrong. Check your connection and try again.";
}
