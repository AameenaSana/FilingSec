/**
 * Thin fetch wrapper for the FilingLens backend. All calls go to the same
 * origin (/api/v1/...), which Next.js proxies to the backend.
 *
 * - Sends cookies (session) with every request.
 * - Echoes the XSRF-TOKEN cookie as the X-XSRF-TOKEN header on state-changing
 *   requests, as required by the backend's CSRF protection.
 * - Converts RFC 9457 ProblemDetail error responses into {@link ApiError}.
 */

const API_BASE = "/api/v1";
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
}

export function readCookie(name: string, cookieString: string = document.cookie): string | undefined {
  return cookieString
    .split("; ")
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (!SAFE_METHODS.has(method)) {
    const token = readCookie(CSRF_COOKIE);
    if (token) {
      headers.set(CSRF_HEADER, decodeURIComponent(token));
    }
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    method,
    headers,
    credentials: "same-origin",
  });

  if (!response.ok) {
    throw new ApiError(response.status, await parseProblem(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function parseProblem(response: Response): Promise<ProblemDetail> {
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return { status: response.status, title: response.statusText };
  }
}
