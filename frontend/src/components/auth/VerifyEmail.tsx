"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Alert } from "@/components/ui/Alert";
import { authApi } from "@/lib/api/auth";
import { errorMessage } from "@/lib/api/client";
import { useLinkToken } from "./useLinkToken";

type Result = { kind: "verified" } | { kind: "failed"; message: string };

export function VerifyEmail() {
  const token = useLinkToken();
  const [result, setResult] = useState<Result | null>(null);
  // Tokens are single-use: never submit the same one twice (e.g. StrictMode re-running effects).
  const request = useRef<{ token: string; promise: Promise<void> } | null>(null);

  useEffect(() => {
    if (!token) {
      return;
    }
    if (request.current?.token !== token) {
      request.current = { token, promise: authApi.verifyEmail(token) };
    }
    let cancelled = false;
    request.current.promise
      .then(() => !cancelled && setResult({ kind: "verified" }))
      .catch((e: unknown) => !cancelled && setResult({ kind: "failed", message: errorMessage(e) }));
    return () => {
      cancelled = true;
    };
  }, [token]);

  if (token === null) {
    return <Failed message="This verification link is incomplete." />;
  }
  if (result === null) {
    return <Alert>Confirming your email address…</Alert>;
  }
  if (result.kind === "failed") {
    return <Failed message={result.message} />;
  }
  return (
    <div className="flex flex-col gap-4">
      <Alert tone="success">Your email address is confirmed.</Alert>
      <Link href="/login" className="text-sm font-medium underline-offset-4 hover:underline">
        Sign in
      </Link>
    </div>
  );
}

function Failed({ message }: { message: string }) {
  return (
    <div className="flex flex-col gap-4">
      <Alert tone="error">{message}</Alert>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        If you already confirmed your address, just sign in. Otherwise, sign in to request a new link.
      </p>
      <Link href="/login" className="text-sm font-medium underline-offset-4 hover:underline">
        Go to sign in
      </Link>
    </div>
  );
}
