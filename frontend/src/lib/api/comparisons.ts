import { apiFetch } from "./client";

export type ComparisonStatus = "WAITING" | "COMPUTING" | "READY" | "FAILED";
export type ChangeType = "ADDED" | "REMOVED" | "MODIFIED" | "MOVED";

export interface ComparedFiling {
  accessionNumber: string;
  formType: string;
  filingDate: string;
  reportDate: string | null;
}

export interface ComparisonStats {
  added: number;
  removed: number;
  modified: number;
  moved: number;
  unchanged: number;
  numericOnly: number;
}

export interface SectionSummary {
  key: string;
  title: string;
  alignment: "MATCHED" | "ADDED" | "REMOVED";
  added: number;
  removed: number;
  modified: number;
  moved: number;
  unchanged: number;
  numericOnly: number;
}

export interface Comparison {
  id: number;
  status: ComparisonStatus;
  detail: string | null;
  company: { cik: string; name: string };
  /** The earlier filing. */
  base: ComparedFiling;
  /** The later filing. */
  target: ComparedFiling;
  stats: ComparisonStats | null;
  computedAt: string | null;
  sections: SectionSummary[];
}

export interface BlockSide {
  ordinal: number;
  ref: string;
  text: string;
}

export interface DiffOp {
  op: "equal" | "insert" | "delete";
  text: string;
}

export interface TextChange {
  ordinal: number;
  type: ChangeType;
  numericOnly: boolean;
  similarity: number | null;
  base: BlockSide | null;
  target: BlockSide | null;
  diff: DiffOp[] | null;
}

export interface ComparisonCandidates {
  suggested: string | null;
  filings: ComparedFiling[];
}

export const comparisonsApi = {
  create: (base: string, target: string) =>
    apiFetch<Comparison>("/comparisons", { method: "POST", body: JSON.stringify({ base, target }) }),

  get: (id: number) => apiFetch<Comparison>(`/comparisons/${id}`),

  changes: (id: number, sectionKey: string, includeNumericOnly: boolean) =>
    apiFetch<TextChange[]>(
      `/comparisons/${id}/sections/${encodeURIComponent(sectionKey)}/changes?includeNumericOnly=${includeNumericOnly}`,
    ),

  candidates: (accession: string) =>
    apiFetch<ComparisonCandidates>(`/filings/${encodeURIComponent(accession)}/comparison-candidates`),
};

export const comparisonKeys = {
  detail: (id: number) => ["comparisons", id] as const,
  changes: (id: number, sectionKey: string, includeNumericOnly: boolean) =>
    ["comparisons", id, "changes", sectionKey, includeNumericOnly] as const,
  candidates: (accession: string) => ["comparisons", "candidates", accession] as const,
};

/** Link to a block in a filing's document reader. */
export function blockLink(accession: string, ordinal: number): string {
  return `/filings/${accession}?block=${ordinal}`;
}
