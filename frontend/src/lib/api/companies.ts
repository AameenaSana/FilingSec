import { apiFetch } from "./client";

export const FORM_TYPES = ["10-K", "10-Q", "8-K"] as const;
export type FormType = (typeof FORM_TYPES)[number];

export interface CompanySummary {
  cik: string;
  name: string;
  tickers: string[];
  exchange: string | null;
}

export interface CompanyDetail extends CompanySummary {
  entityType: string | null;
  sic: string | null;
  sicDescription: string | null;
  category: string | null;
  fiscalYearEnd: string | null;
  stateOfIncorporation: string | null;
  edgarUrl: string;
  profileSyncedAt: string | null;
  watchlistItemId: number | null;
}

export interface WatchlistEntry {
  id: number;
  company: CompanySummary;
  formTypes: FormType[];
  notificationsEnabled: boolean;
  createdAt: string;
}

const json = (body: unknown) => JSON.stringify(body);

export const companiesApi = {
  search: (q: string, limit = 10) =>
    apiFetch<CompanySummary[]>(`/companies?${new URLSearchParams({ q, limit: String(limit) })}`),

  get: (cik: string) => apiFetch<CompanyDetail>(`/companies/${encodeURIComponent(cik)}`),
};

export const watchlistApi = {
  list: () => apiFetch<WatchlistEntry[]>("/watchlist"),

  add: (cik: string) => apiFetch<WatchlistEntry>("/watchlist", { method: "POST", body: json({ cik }) }),

  update: (id: number, formTypes: FormType[], notificationsEnabled: boolean) =>
    apiFetch<WatchlistEntry>(`/watchlist/${id}`, {
      method: "PATCH",
      body: json({ formTypes, notificationsEnabled }),
    }),

  remove: (id: number) => apiFetch<void>(`/watchlist/${id}`, { method: "DELETE" }),
};

export const queryKeys = {
  companySearch: (q: string) => ["companies", "search", q] as const,
  company: (cik: string) => ["companies", "detail", cik] as const,
  watchlist: ["watchlist"] as const,
};

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** Formats SEC's MMDD fiscal year end, e.g. "0927" → "Sep 27". */
export function formatFiscalYearEnd(mmdd: string | null): string | null {
  if (!mmdd || !/^\d{4}$/.test(mmdd)) {
    return null;
  }
  const month = Number(mmdd.slice(0, 2));
  const day = Number(mmdd.slice(2));
  return month >= 1 && month <= 12 ? `${MONTHS[month - 1]} ${day}` : null;
}
