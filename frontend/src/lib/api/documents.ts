import { apiFetch } from "./client";

export type ParseStatus = "NOT_AVAILABLE" | "PARSING" | "PARSED" | "UNSUPPORTED" | "FAILED";

export interface DocumentSection {
  key: string;
  title: string;
  headingText: string | null;
  ordinal: number;
  firstBlock: number;
  lastBlock: number;
  confidence: "HIGH" | "MEDIUM" | "LOW";
}

export interface DocumentStructure {
  status: ParseStatus;
  detail: string | null;
  parserVersion: number | null;
  parsedAt: string | null;
  blockCount: number | null;
  sections: DocumentSection[];
}

const base = (accession: string) => `/filings/${encodeURIComponent(accession)}`;

export const documentsApi = {
  structure: (accession: string) => apiFetch<DocumentStructure>(`${base(accession)}/structure`),
  reparse: (accession: string) => apiFetch<DocumentStructure>(`${base(accession)}/parse`, { method: "POST" }),
};

/** URL of the sanitized, annotated source document (served for the sandboxed viewer frame). */
export function viewerUrl(accession: string): string {
  return `/api/v1${base(accession)}/source`;
}

export const documentKeys = {
  structure: (accession: string) => ["documents", "structure", accession] as const,
};

/** "Item 1A · Risk Factors" from a 10-K key, "Part II, Item 1A · Risk Factors" for 10-Q keys. */
export function sectionLabel(section: Pick<DocumentSection, "key" | "title">): string {
  const match = /^(?:\w+)\.(?:PART_(I{1,3}|IV)\.)?ITEM_([\w.]+)$/.exec(section.key);
  if (!match) {
    return section.title;
  }
  const [, part, item] = match;
  return `${part ? `Part ${part}, ` : ""}Item ${item} · ${section.title}`;
}
