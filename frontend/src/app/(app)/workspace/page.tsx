import type { Metadata } from "next";
import { WorkspaceView } from "@/components/workspace/WorkspaceView";

export const metadata: Metadata = { title: "Workspace · FilingLens" };

export default function WorkspacePage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Workspace</h1>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
          Findings you have saved, each quoting the filing verbatim with a link to its source.
        </p>
      </div>
      <WorkspaceView />
    </div>
  );
}
