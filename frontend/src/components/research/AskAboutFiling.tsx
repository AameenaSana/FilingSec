"use client";

import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { errorMessage } from "@/lib/api/client";
import { researchApi } from "@/lib/api/research";

/** Starts a Q&A conversation grounded in this filing. */
export function AskAboutFiling({ accession }: { accession: string }) {
  const router = useRouter();
  const start = useMutation({
    mutationFn: () => researchApi.create([accession]),
    onSuccess: (conversation) => router.push(`/research/${conversation.id}`),
  });
  return (
    <div className="flex flex-col gap-1">
      <Button type="button" variant="secondary" pending={start.isPending} onClick={() => start.mutate()}>
        Ask about this filing
      </Button>
      {start.error && (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          {errorMessage(start.error)}
        </p>
      )}
    </div>
  );
}
