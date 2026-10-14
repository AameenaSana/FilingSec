import Link from "next/link";
import { BackendStatus } from "@/components/BackendStatus";

export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col justify-center gap-6 px-6 py-24">
      <h1 className="text-4xl font-semibold tracking-tight">FilingLens</h1>
      <p className="max-w-xl text-lg leading-8 text-zinc-600 dark:text-zinc-400">
        SEC filing intelligence: track companies, compare 10-K, 10-Q and 8-K filings across periods, and ask
        questions answered with verifiable citations to the source text.
      </p>
      <div className="flex gap-3">
        <Link
          href="/register"
          className="inline-flex h-10 items-center rounded-md bg-zinc-900 px-4 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          Create an account
        </Link>
        <Link
          href="/login"
          className="inline-flex h-10 items-center rounded-md border border-zinc-300 px-4 text-sm font-medium hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-900"
        >
          Sign in
        </Link>
      </div>
      <BackendStatus />
    </main>
  );
}
