import { API_BASE, ApiError, apiFetch, parseProblem } from "./client";

/**
 * What to save as a finding. The server reads the quote and source details from
 * the referenced citation, change or passage; the client never supplies text.
 */
export type FindingSource =
  | { type: "CITATION"; citationId: number }
  | {
      type: "CHANGE";
      comparisonId: number;
      sectionKey: string;
      changeOrdinal: number;
    }
  | { type: "PASSAGE"; accessionNumber: string; blockOrdinal: number };

export interface FindingFiling {
  accessionNumber: string;
  cik: string;
  companyName: string;
  formType: string;
  reportDate: string | null;
}

export interface Finding {
  id: number;
  sessionId: number | null;
  sourceType: FindingSource["type"];
  filing: FindingFiling;
  sectionKey: string;
  sectionTitle: string;
  blockRefs: string[];
  blockOrdinal: number;
  quotedText: string;
  previousText: string | null;
  previousAccession: string | null;
  previousBlockOrdinal: number | null;
  changeType: "ADDED" | "REMOVED" | "MODIFIED" | "MOVED" | null;
  comparisonId: number | null;
  changeOrdinal: number | null;
  conversationId: number | null;
  note: string | null;
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface SessionSummary {
  id: number;
  title: string;
  description: string | null;
  findingCount: number;
  updatedAt: string;
}

export interface ResearchSession {
  id: number;
  title: string;
  description: string | null;
  findings: Finding[];
  createdAt: string;
  updatedAt: string;
}

export interface FindingFilter {
  sessionId?: number;
  unfiled?: boolean;
  q?: string;
  tag?: string;
}

export interface FindingChanges {
  sessionId: number | null;
  note: string | null;
  tags: string[];
}

export const workspaceApi = {
  sessions: () => apiFetch<SessionSummary[]>("/research-sessions"),
  session: (id: number) => apiFetch<ResearchSession>(`/research-sessions/${id}`),
  createSession: (title: string, description?: string) =>
    apiFetch<ResearchSession>("/research-sessions", {
      method: "POST",
      body: JSON.stringify({ title, description }),
    }),
  updateSession: (id: number, title: string, description: string | null) =>
    apiFetch<ResearchSession>(`/research-sessions/${id}`, {
      method: "PATCH",
      body: JSON.stringify({ title, description }),
    }),
  deleteSession: (id: number) => apiFetch<void>(`/research-sessions/${id}`, { method: "DELETE" }),
  findings: (filter: FindingFilter = {}) => {
    const params = new URLSearchParams();
    if (filter.sessionId) params.set("sessionId", String(filter.sessionId));
    if (filter.unfiled) params.set("unfiled", "true");
    if (filter.q?.trim()) params.set("q", filter.q.trim());
    if (filter.tag) params.set("tag", filter.tag);
    const query = params.toString();
    return apiFetch<Finding[]>(`/findings${query ? `?${query}` : ""}`);
  },
  saveFinding: (source: FindingSource, options: { sessionId?: number | null; note?: string; tags?: string[] } = {}) =>
    apiFetch<Finding>("/findings", {
      method: "POST",
      body: JSON.stringify({
        source,
        sessionId: options.sessionId ?? null,
        note: options.note,
        tags: options.tags,
      }),
    }),
  updateFinding: (id: number, changes: FindingChanges) =>
    apiFetch<Finding>(`/findings/${id}`, {
      method: "PATCH",
      body: JSON.stringify(changes),
    }),
  deleteFinding: (id: number) => apiFetch<void>(`/findings/${id}`, { method: "DELETE" }),
};

export const workspaceKeys = {
  all: ["workspace"] as const,
  sessions: ["workspace", "sessions"] as const,
  session: (id: number) => ["workspace", "sessions", id] as const,
  findings: (filter: FindingFilter) => ["workspace", "findings", filter] as const,
};

/** Downloads a session as a Markdown file. */
export async function downloadSessionMarkdown(id: number): Promise<void> {
  const response = await fetch(`${API_BASE}/research-sessions/${id}/export`, {
    credentials: "same-origin",
  });
  if (!response.ok) {
    throw new ApiError(response.status, await parseProblem(response));
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = `research-session-${id}.md`;
  link.click();
  URL.revokeObjectURL(url);
}

/** Splits comma-separated tag input into tags. */
export function parseTags(input: string): string[] {
  return [
    ...new Set(
      input
        .split(",")
        .map((t) => t.trim().toLowerCase().replace(/\s+/g, "-"))
        .filter(Boolean),
    ),
  ];
}
