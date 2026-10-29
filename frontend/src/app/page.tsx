import Link from "next/link";
import { BackendStatus } from "@/components/BackendStatus";
import { Icon, type IconName } from "@/components/ui/Icon";

const FEATURES: { icon: IconName; title: string; body: string }[] = [
  { icon: "star", title: "Watch companies", body: "Get notified the moment a 10-K, 10-Q or 8-K lands on EDGAR." },
  { icon: "diff", title: "See what changed", body: "Each new report is diffed against the previous period, section by section." },
  { icon: "chat", title: "Ask with citations", body: "Answers quote the filing text, and every citation is verified." },
  { icon: "bookmark", title: "Save findings", body: "Collect cited passages into research sessions and export them." },
];

export default function Home() {
  return (
    <main className="relative isolate flex flex-1 flex-col overflow-hidden">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 -top-40 -z-10 mx-auto h-[36rem] max-w-4xl rounded-full bg-linear-to-tr from-violet-400/30 via-indigo-300/20 to-sky-300/30 blur-3xl dark:from-violet-600/25 dark:via-indigo-600/15 dark:to-sky-600/20"
      />
      <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col justify-center gap-12 px-6 py-24">
        <div className="flex animate-fade-in-up flex-col gap-6">
          <span className="inline-flex w-fit items-center gap-2 rounded-full border border-violet-200 bg-white/70 px-3 py-1 text-xs font-medium text-violet-700 backdrop-blur dark:border-violet-500/30 dark:bg-violet-500/10 dark:text-violet-300">
            <Icon name="sparkle" className="size-3.5" />
            SEC filing intelligence
          </span>
          <h1 className="max-w-3xl text-4xl font-semibold tracking-tight sm:text-6xl">
            Read filings{" "}
            <span className="bg-linear-to-r from-violet-600 to-indigo-500 bg-clip-text text-transparent dark:from-violet-400 dark:to-sky-400">
              faster
            </span>
            , with proof.
          </h1>
          <p className="max-w-xl text-lg leading-8 text-zinc-600 dark:text-zinc-400">
            Track companies, compare 10-K, 10-Q and 8-K filings across periods, and ask questions answered with
            verifiable citations to the source text.
          </p>
          <div className="flex flex-wrap gap-3">
            <Link
              href="/register"
              className="group inline-flex h-11 items-center gap-2 rounded-lg bg-violet-600 px-5 text-sm font-medium text-white shadow-md shadow-violet-600/25 transition hover:bg-violet-500 hover:shadow-lg hover:shadow-violet-600/30 active:scale-[0.97]"
            >
              Create an account
              <Icon name="arrowRight" className="size-4 transition-transform group-hover:translate-x-0.5" />
            </Link>
            <Link
              href="/login"
              className="inline-flex h-11 items-center rounded-lg border border-zinc-300 bg-white/70 px-5 text-sm font-medium backdrop-blur transition hover:border-zinc-400 hover:bg-white active:scale-[0.97] dark:border-zinc-700 dark:bg-zinc-900/70 dark:hover:bg-zinc-900"
            >
              Sign in
            </Link>
          </div>
          <BackendStatus />
        </div>
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {FEATURES.map((feature, index) => (
            <li
              key={feature.title}
              style={{ animationDelay: `${150 + index * 80}ms` }}
              className="group animate-fade-in-up rounded-xl border border-zinc-200 bg-white/80 p-5 backdrop-blur transition hover:-translate-y-1 hover:border-violet-300 hover:shadow-lg hover:shadow-violet-500/5 dark:border-zinc-800 dark:bg-zinc-950/70 dark:hover:border-violet-500/40"
            >
              <span className="mb-3 grid size-9 place-items-center rounded-lg bg-violet-100 text-violet-700 transition-transform group-hover:scale-110 group-hover:-rotate-6 dark:bg-violet-500/15 dark:text-violet-300">
                <Icon name={feature.icon} />
              </span>
              <h2 className="font-medium">{feature.title}</h2>
              <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{feature.body}</p>
            </li>
          ))}
        </ul>
      </div>
    </main>
  );
}
