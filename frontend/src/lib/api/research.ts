import { API_BASE, ApiError, apiFetch, ensureCsrfToken, parseProblem } from "./client";

export interface ResearchFiling {
  accessionNumber: string;
  cik: string;
  companyName: string;
  formType: string;
  reportDate: string | null;
  filingDate: string;
}

export interface FilingReadiness {
  accessionNumber: string;
  status: "PREPARING" | "READY" | "UNAVAILABLE";
  semanticSearch: boolean;
}

export interface Citation {
  ordinal: number;
  filing: ResearchFiling;
  sectionKey: string;
  sectionTitle: string;
  blockRefs: string[];
  firstBlockOrdinal: number;
  citedText: string;
  answerStart: number;
  answerEnd: number;
  verified: boolean;
}

export interface ResearchMessage {
  id: number;
  role: "USER" | "ASSISTANT";
  content: string;
  status: "COMPLETE" | "STREAMING" | "REFUSED" | "FAILED";
  createdAt: string;
  citations: Citation[];
}

export interface ConversationSummary {
  id: number;
  title: string;
  filings: ResearchFiling[];
  updatedAt: string;
}

export interface Conversation extends ConversationSummary {
  readiness: FilingReadiness[];
  messages: ResearchMessage[];
  aiAvailable: boolean;
}

export const researchApi = {
  create: (filings: string[], title?: string) =>
    apiFetch<Conversation>("/conversations", { method: "POST", body: JSON.stringify({ filings, title }) }),
  list: () => apiFetch<ConversationSummary[]>("/conversations"),
  createCrossCompany: (ciks: string[], formType: string, fiscalYear: number | null, topic: string) =>
    apiFetch<Conversation>("/conversations/cross-company", {
      method: "POST",
      body: JSON.stringify({ ciks, formType, fiscalYear, topic: topic || undefined }),
    }),
  get: (id: number) => apiFetch<Conversation>(`/conversations/${id}`),
  rename: (id: number, title: string) =>
    apiFetch<void>(`/conversations/${id}`, { method: "PATCH", body: JSON.stringify({ title }) }),
  remove: (id: number) => apiFetch<void>(`/conversations/${id}`, { method: "DELETE" }),
};

export const researchKeys = {
  list: ["conversations"] as const,
  detail: (id: number) => ["conversations", id] as const,
};

export interface StreamHandlers {
  onStart?: (ids: { userMessageId: number; assistantMessageId: number }) => void;
  onDelta: (text: string) => void;
  onDone: (message: ResearchMessage) => void;
  onError: (message: string) => void;
}

/**
 * Asks a question and consumes the server-sent event stream of the answer.
 * Errors raised before streaming starts (validation, not ready, quota) are
 * thrown as {@link ApiError}.
 */
export async function streamQuestion(conversationId: number, content: string, handlers: StreamHandlers): Promise<void> {
  const token = await ensureCsrfToken();
  const response = await fetch(`${API_BASE}/conversations/${conversationId}/messages`, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream, application/problem+json",
      ...(token ? { "X-XSRF-TOKEN": token } : {}),
    },
    body: JSON.stringify({ content }),
  });
  if (!response.ok || !response.body) {
    throw new ApiError(response.status, await parseProblem(response));
  }
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += value;
    let boundary: number;
    while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const raw = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, "");
      dispatch(raw, handlers);
    }
  }
  if (buffer.trim()) {
    dispatch(buffer, handlers);
  }
}

function dispatch(raw: string, handlers: StreamHandlers) {
  let event = "message";
  const data: string[] = [];
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith("event:")) {
      event = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      data.push(line.slice(5).replace(/^ /, ""));
    }
  }
  if (data.length === 0) {
    return;
  }
  const payload = JSON.parse(data.join("\n"));
  switch (event) {
    case "start":
      handlers.onStart?.(payload);
      break;
    case "delta":
      handlers.onDelta(payload.text);
      break;
    case "done":
      handlers.onDone(payload);
      break;
    case "error":
      handlers.onError(payload.message);
      break;
  }
}
