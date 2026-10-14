import { apiFetch } from "./client";

export interface DashboardFiling {
  accessionNumber: string;
  formType: string;
  filingDate: string;
  reportDate: string | null;
}

export interface WatchedCompany {
  cik: string;
  name: string;
  ticker: string | null;
  formTypes: string[];
  latestFiling: DashboardFiling | null;
  unreadNotifications: number;
  lastCheckedAt: string | null;
}

export interface RecentFiling extends DashboardFiling {
  cik: string;
  companyName: string;
  ticker: string | null;
  /** 8-K item numbers, comma-separated. */
  items: string | null;
}

export interface RecentChange {
  comparisonId: number;
  companyName: string;
  ticker: string | null;
  formType: string;
  basePeriod: string;
  targetPeriod: string;
  added: number;
  removed: number;
  modified: number;
  moved: number;
  computedAt: string;
}

export interface Dashboard {
  summary: {
    watchedCompanies: number;
    filingsLast30Days: number;
    unreadNotifications: number;
    findings: number;
    lastCheckedAt: string | null;
  };
  companies: WatchedCompany[];
  recentFilings: RecentFiling[];
  recentChanges: RecentChange[];
  notifications: { id: number; type: string; title: string; link: string; createdAt: string; read: boolean }[];
  conversations: { id: number; title: string; updatedAt: string }[];
  sessions: { id: number; title: string; findingCount: number; updatedAt: string }[];
}

export const dashboardApi = {
  get: () => apiFetch<Dashboard>("/dashboard"),
};

export const dashboardKeys = {
  all: ["dashboard"] as const,
};
