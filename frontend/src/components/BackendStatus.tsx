"use client";

import { useEffect, useState } from "react";
import { getSystemInfo, type SystemInfo } from "@/lib/api/system";

type Status = { state: "loading" } | { state: "ok"; info: SystemInfo } | { state: "error" };

/** Shows whether the frontend can reach the backend API. */
export function BackendStatus() {
  const [status, setStatus] = useState<Status>({ state: "loading" });

  useEffect(() => {
    let cancelled = false;
    getSystemInfo()
      .then((info) => !cancelled && setStatus({ state: "ok", info }))
      .catch(() => !cancelled && setStatus({ state: "error" }));
    return () => {
      cancelled = true;
    };
  }, []);

  const dot =
    status.state === "ok" ? "bg-emerald-500" : status.state === "error" ? "bg-red-500" : "bg-zinc-400";
  const label =
    status.state === "ok"
      ? `API connected · v${status.info.version}`
      : status.state === "error"
        ? "API unreachable"
        : "Checking API…";

  return (
    <p role="status" className="inline-flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
      <span aria-hidden className={`h-2 w-2 rounded-full ${dot}`} />
      {label}
    </p>
  );
}
