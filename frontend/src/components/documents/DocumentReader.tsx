"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { useCallback, useRef, useState } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { errorMessage } from "@/lib/api/client";
import { documentKeys, documentsApi, sectionLabel, viewerUrl } from "@/lib/api/documents";

const POLL_MS = 2000;

/**
 * The filing's source document in a sandboxed frame with section navigation.
 * `?block=N` in the page URL highlights block N once the document has loaded.
 */
export function DocumentReader({ accession }: { accession: string }) {
  const queryClient = useQueryClient();
  const searchParams = useSearchParams();
  const frame = useRef<HTMLIFrameElement>(null);
  const [activeSection, setActiveSection] = useState<string | null>(null);

  const { data: structure, error } = useQuery({
    queryKey: documentKeys.structure(accession),
    queryFn: () => documentsApi.structure(accession),
    refetchInterval: (query) => (query.state.data?.status === "PARSING" ? POLL_MS : false),
  });
  const reparse = useMutation({
    mutationFn: () => documentsApi.reparse(accession),
    onSuccess: (data) => queryClient.setQueryData(documentKeys.structure(accession), data),
  });

  const showBlock = useCallback((ordinal: number) => {
    const document = frame.current?.contentDocument;
    const target = document?.querySelector(`[data-fl-block="${ordinal}"]`);
    if (!document || !target) {
      return false;
    }
    document.querySelectorAll("[data-fl-highlight]").forEach((el) => el.removeAttribute("data-fl-highlight"));
    target.setAttribute("data-fl-highlight", "");
    target.scrollIntoView({ block: "start" });
    return true;
  }, []);

  const onFrameLoad = useCallback(() => {
    const requested = Number(searchParams.get("block"));
    if (Number.isInteger(requested) && requested > 0) {
      showBlock(requested);
    }
  }, [searchParams, showBlock]);

  if (error) {
    return <Alert tone="error">{errorMessage(error)}</Alert>;
  }
  if (!structure || structure.status === "PARSING" || structure.status === "NOT_AVAILABLE") {
    return (
      <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
        Preparing the document…
      </p>
    );
  }
  if (structure.status === "UNSUPPORTED") {
    return <Alert>{structure.detail ?? "This document format cannot be displayed yet."} Use the SEC link above.</Alert>;
  }
  if (structure.status === "FAILED") {
    return (
      <Alert tone="error">
        <div className="flex items-center justify-between gap-3">
          <span>{structure.detail ?? "The document could not be processed."}</span>
          <Button
            type="button"
            variant="secondary"
            className="h-8 px-3 text-xs"
            pending={reparse.isPending}
            onClick={() => reparse.mutate()}
          >
            Try again
          </Button>
        </div>
      </Alert>
    );
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[18rem_1fr]">
      <nav aria-label="Sections" className="lg:max-h-[75vh] lg:overflow-y-auto">
        <ol className="flex flex-col gap-0.5 text-sm">
          {structure.sections.map((section) => (
            <li key={section.key}>
              <button
                type="button"
                aria-current={activeSection === section.key ? "true" : undefined}
                onClick={() => {
                  setActiveSection(section.key);
                  showBlock(section.firstBlock);
                }}
                className="w-full rounded px-2 py-1.5 text-left hover:bg-zinc-100 aria-[current=true]:bg-zinc-200 dark:hover:bg-zinc-800 dark:aria-[current=true]:bg-zinc-800"
              >
                {sectionLabel(section)}
                {section.confidence === "LOW" && (
                  <span className="ml-1 text-xs text-zinc-500" title="Section boundary detected with low confidence">
                    (approx.)
                  </span>
                )}
              </button>
            </li>
          ))}
        </ol>
      </nav>
      <iframe
        ref={frame}
        title="Filing document"
        src={viewerUrl(accession)}
        // No scripts may run in the filing; same-origin lets this page scroll to and highlight blocks.
        sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
        referrerPolicy="no-referrer"
        onLoad={onFrameLoad}
        className="h-[75vh] w-full rounded-lg border border-zinc-200 bg-white dark:border-zinc-800"
      />
    </div>
  );
}
