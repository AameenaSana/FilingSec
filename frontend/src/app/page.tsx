import { BackendStatus } from "@/components/BackendStatus";

export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col justify-center gap-6 px-6 py-24">
      <h1 className="text-4xl font-semibold tracking-tight">FilingLens</h1>
      <p className="max-w-xl text-lg leading-8 text-zinc-600 dark:text-zinc-400">
        SEC filing intelligence: track companies, compare 10-K, 10-Q and 8-K filings across periods, and ask
        questions answered with verifiable citations to the source text.
      </p>
      <BackendStatus />
    </main>
  );
}
