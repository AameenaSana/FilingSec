import { apiFetch } from "./client";
import type { FormType } from "./companies";

export type FilingStatus = "DISCOVERED" | "FETCHING" | "FETCHED" | "FAILED";

export interface FilingSummary {
  accessionNumber: string;
  formType: string;
  filingDate: string;
  /** Period of report. */
  reportDate: string | null;
  acceptanceAt: string | null;
  description: string | null;
  items: string[];
  primaryDocumentUrl: string | null;
  status: FilingStatus;
}

export interface FilingPage {
  items: FilingSummary[];
  nextCursor: string | null;
  /** SEC was unreachable; recent filings may be missing. */
  stale: boolean;
}

export interface FilingDocument {
  sequence: number;
  filename: string;
  type: string | null;
  description: string | null;
  sizeBytes: number | null;
  primary: boolean;
  sourceUrl: string;
  stored: boolean;
  sha256: string | null;
}

export interface FilingDetail extends Omit<FilingSummary, "primaryDocumentUrl"> {
  company: { cik: string; name: string };
  sizeBytes: number | null;
  xbrl: boolean;
  inlineXbrl: boolean;
  fileNumber: string | null;
  statusDetail: string | null;
  fetchedAt: string | null;
  filingIndexUrl: string;
  primaryDocumentUrl: string | null;
  documents: FilingDocument[];
}

export const filingsApi = {
  list: (cik: string, forms: FormType[], cursor?: string | null, limit = 25) => {
    const params = new URLSearchParams({ limit: String(limit) });
    forms.forEach((form) => params.append("forms", form));
    if (cursor) {
      params.set("cursor", cursor);
    }
    return apiFetch<FilingPage>(`/companies/${encodeURIComponent(cik)}/filings?${params}`);
  },

  get: (accession: string) => apiFetch<FilingDetail>(`/filings/${encodeURIComponent(accession)}`),

  requestFetch: (accession: string) =>
    apiFetch<FilingDetail>(`/filings/${encodeURIComponent(accession)}/fetch`, { method: "POST" }),
};

export const filingKeys = {
  list: (cik: string, forms: FormType[]) => ["filings", "list", cik, [...forms].sort().join(",")] as const,
  detail: (accession: string) => ["filings", "detail", accession] as const,
};

/** 8-K item numbers and their titles (Form 8-K General Instructions). */
export const EIGHT_K_ITEMS: Record<string, string> = {
  "1.01": "Entry into a Material Definitive Agreement",
  "1.02": "Termination of a Material Definitive Agreement",
  "1.03": "Bankruptcy or Receivership",
  "1.04": "Mine Safety",
  "1.05": "Material Cybersecurity Incidents",
  "2.01": "Completion of Acquisition or Disposition of Assets",
  "2.02": "Results of Operations and Financial Condition",
  "2.03": "Creation of a Direct Financial Obligation",
  "2.04": "Triggering Events That Accelerate a Financial Obligation",
  "2.05": "Costs Associated with Exit or Disposal Activities",
  "2.06": "Material Impairments",
  "3.01": "Notice of Delisting or Failure to Satisfy a Listing Rule",
  "3.02": "Unregistered Sales of Equity Securities",
  "3.03": "Material Modification to Rights of Security Holders",
  "4.01": "Changes in Registrant's Certifying Accountant",
  "4.02": "Non-Reliance on Previously Issued Financial Statements",
  "5.01": "Changes in Control of Registrant",
  "5.02": "Departure or Appointment of Directors or Officers; Compensation Arrangements",
  "5.03": "Amendments to Articles of Incorporation or Bylaws; Change in Fiscal Year",
  "5.04": "Temporary Suspension of Trading Under Employee Benefit Plans",
  "5.05": "Amendments to the Code of Ethics, or Waiver",
  "5.06": "Change in Shell Company Status",
  "5.07": "Submission of Matters to a Vote of Security Holders",
  "5.08": "Shareholder Director Nominations",
  "6.01": "ABS Informational and Computational Material",
  "6.02": "Change of Servicer or Trustee",
  "6.03": "Change in Credit Enhancement or Other External Support",
  "6.04": "Failure to Make a Required Distribution",
  "6.05": "Securities Act Updating Disclosure",
  "7.01": "Regulation FD Disclosure",
  "8.01": "Other Events",
  "9.01": "Financial Statements and Exhibits",
};

export function describeItem(item: string): string {
  const title = EIGHT_K_ITEMS[item];
  return title ? `Item ${item} · ${title}` : `Item ${item}`;
}

export function formatBytes(bytes: number | null): string | null {
  if (bytes === null) {
    return null;
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

/** Formats an ISO date (YYYY-MM-DD) without timezone shifts, e.g. "Oct 31, 2025". */
export function formatDate(isoDate: string | null): string | null {
  if (!isoDate) {
    return null;
  }
  const [year, month, day] = isoDate.slice(0, 10).split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day)).toLocaleDateString("en-US", {
    timeZone: "UTC",
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}
