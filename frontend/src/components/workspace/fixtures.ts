import type { Finding } from "@/lib/api/workspace";

export function finding(extra: Partial<Finding> = {}): Finding {
  return {
    id: 1,
    sessionId: null,
    sourceType: "CITATION",
    filing: {
      accessionNumber: "0000320193-25-000079",
      cik: "0000320193",
      companyName: "Apple Inc.",
      formType: "10-K",
      reportDate: "2025-09-27",
    },
    sectionKey: "10K.ITEM_1A",
    sectionTitle: "Risk Factors",
    blockRefs: ["10K.ITEM_1A/p4"],
    blockOrdinal: 42,
    quotedText: "AI features may not be successful.",
    previousText: null,
    previousAccession: null,
    previousBlockOrdinal: null,
    changeType: null,
    comparisonId: null,
    changeOrdinal: null,
    conversationId: 11,
    note: null,
    tags: [],
    createdAt: "2026-06-01T12:00:00Z",
    updatedAt: "2026-06-01T12:00:00Z",
    ...extra,
  };
}
