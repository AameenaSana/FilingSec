"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useState, type CSSProperties, type ReactNode } from "react";
import { Alert } from "@/components/ui/Alert";
import { Icon, Spinner, type IconName } from "@/components/ui/Icon";
import { Skeleton } from "@/components/ui/Skeleton";
import { errorMessage } from "@/lib/api/client";
import { dashboardApi, dashboardKeys, type Dashboard, type RecentChange } from "@/lib/api/dashboard";
import { describeItem, formatDate } from "@/lib/api/filings";
import { timeAgo } from "@/lib/api/notifications";
import { useAuth } from "@/lib/auth/AuthProvider";

/** Overview of watched companies, their new filings and changes, notifications and research. */
export function DashboardView() {
  const { state } = useAuth();
  const { data, error, isPending, isFetching, refetch } = useQuery({
    queryKey: dashboardKeys.all,
    queryFn: dashboardApi.get,
    refetchInterval: 120_000,
  });

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-sm text-zinc-500">{greeting()}</p>
          <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Welcome, {state.user?.displayName}</h1>
        </div>
        {data && (
          <button
            type="button"
            onClick={() => refetch()}
            disabled={isFetching}
            className="flex items-center gap-1.5 rounded-lg border border-zinc-200 bg-white px-3 py-1.5 text-xs font-medium text-zinc-600 transition hover:border-zinc-300 hover:text-zinc-900 active:scale-95 disabled:opacity-70 dark:border-zinc-800 dark:bg-zinc-950 dark:text-zinc-400 dark:hover:text-zinc-100"
          >
            {isFetching ? <Spinner className="size-3.5" /> : <Icon name="refresh" className="size-3.5" />}
            {isFetching ? "Refreshing" : "Refresh"}
          </button>
        )}
      </div>
      {isPending && <DashboardSkeleton />}
      {error && <Alert tone="error">{errorMessage(error)}</Alert>}
      {data && (data.companies.length === 0 ? <GettingStarted /> : <Overview data={data} />)}
    </div>
  );
}

function GettingStarted() {
  const steps: { icon: IconName; body: ReactNode }[] = [
    {
      icon: "search",
      body: (
        <>
          <Link href="/companies" className="font-medium text-violet-700 underline underline-offset-4 dark:text-violet-400">
            Search for companies
          </Link>{" "}
          and add them to your watchlist.
        </>
      ),
    },
    { icon: "bell", body: "FilingLens checks SEC for their new 10-K, 10-Q and 8-K filings and notifies you." },
    { icon: "diff", body: "New annual and quarterly reports are compared with the previous period automatically." },
    { icon: "bookmark", body: "Ask questions about any filing and save the cited passages to your workspace." },
  ];
  return (
    <Panel title="Get started">
      <ol className="grid gap-3 sm:grid-cols-2">
        {steps.map((step, index) => (
          <li
            key={index}
            style={stagger(index)}
            className="flex animate-fade-in-up gap-3 rounded-lg border border-zinc-200 p-4 text-sm text-zinc-700 transition hover:-translate-y-0.5 hover:border-violet-300 hover:shadow-sm dark:border-zinc-800 dark:text-zinc-300 dark:hover:border-violet-500/50"
          >
            <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-violet-100 text-violet-700 dark:bg-violet-500/15 dark:text-violet-300">
              <Icon name={step.icon} />
            </span>
            <span>
              <span className="mb-0.5 block text-xs font-medium text-zinc-400">Step {index + 1}</span>
              {step.body}
            </span>
          </li>
        ))}
      </ol>
    </Panel>
  );
}

function Overview({ data }: { data: Dashboard }) {
  const { summary } = data;
  const [formFilter, setFormFilter] = useState<string | null>(null);
  const formTypes = [...new Set(data.recentFilings.map((f) => f.formType))].sort();
  const filings = formFilter ? data.recentFilings.filter((f) => f.formType === formFilter) : data.recentFilings;
  return (
    <>
      <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat index={0} icon="star" label="Watched companies" value={summary.watchedCompanies} href="/watchlist" />
        <Stat index={1} icon="file" label="Filings in the last 30 days" value={summary.filingsLast30Days} />
        <Stat
          index={2}
          icon="bell"
          label="Unread notifications"
          value={summary.unreadNotifications}
          href="/notifications"
          highlight={summary.unreadNotifications > 0}
        />
        <Stat index={3} icon="bookmark" label="Saved findings" value={summary.findings} href="/workspace" />
      </dl>
      <p className="-mt-3 flex items-center gap-1.5 text-xs text-zinc-500">
        <span aria-hidden className={`size-1.5 rounded-full ${summary.lastCheckedAt ? "bg-emerald-500" : "bg-zinc-400"}`} />
        {summary.lastCheckedAt
          ? `SEC last checked for new filings ${timeAgo(summary.lastCheckedAt)}.`
          : "Watched companies have not been checked with SEC yet."}
      </p>

      <div className="grid gap-6 lg:grid-cols-[3fr_2fr]">
        <div className="flex min-w-0 flex-col gap-6">
          <Panel title="What changed" action={<span className="text-xs text-zinc-500">Latest comparisons</span>}>
            {data.recentChanges.length === 0 ? (
              <Empty>
                When a watched company files a new 10-K or 10-Q, it is compared with the previous period and the changes
                appear here.
              </Empty>
            ) : (
              <ul className="flex flex-col divide-y divide-zinc-200 dark:divide-zinc-800">
                {data.recentChanges.map((change) => (
                  <li key={change.comparisonId} className="py-1">
                    <ChangeRow change={change} />
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel
            title="Recent filings"
            action={
              formTypes.length > 1 ? (
                <div role="group" aria-label="Filter by form" className="flex gap-1">
                  {[null, ...formTypes].map((form) => (
                    <button
                      key={form ?? "all"}
                      type="button"
                      aria-pressed={formFilter === form}
                      onClick={() => setFormFilter(form)}
                      className="rounded-full border border-zinc-200 px-2.5 py-0.5 text-xs text-zinc-600 transition hover:border-zinc-300 active:scale-95 aria-pressed:border-violet-600 aria-pressed:bg-violet-600 aria-pressed:text-white dark:border-zinc-700 dark:text-zinc-400 dark:aria-pressed:border-violet-500 dark:aria-pressed:bg-violet-500"
                    >
                      {form ?? "All"}
                    </button>
                  ))}
                </div>
              ) : (
                <span className="text-xs text-zinc-500">Last 90 days</span>
              )
            }
          >
            {data.recentFilings.length === 0 ? (
              <Empty>No filings of the forms you follow in the last 90 days.</Empty>
            ) : (
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-zinc-500">
                  <tr>
                    <th className="pr-3 pb-2 font-medium">Company</th>
                    <th className="pr-3 pb-2 font-medium">Form</th>
                    <th className="pr-3 pb-2 font-medium">Filed</th>
                    <th className="hidden pb-2 font-medium sm:table-cell">Period / items</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
                  {filings.map((filing) => (
                    <tr
                      key={filing.accessionNumber}
                      className="animate-fade-in transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-900/60"
                    >
                      <td className="py-2 pr-3">
                        <Link href={`/companies/${filing.cik}`} className="font-medium hover:underline">
                          {filing.ticker ?? filing.companyName}
                        </Link>
                      </td>
                      <td className="py-2 pr-3">
                        <Link
                          href={`/filings/${filing.accessionNumber}`}
                          className={`inline-block rounded-md px-1.5 py-0.5 text-xs font-semibold whitespace-nowrap transition hover:ring-2 ${formBadge(filing.formType)}`}
                        >
                          {filing.formType}
                        </Link>
                      </td>
                      <td className="py-2 pr-3 whitespace-nowrap text-zinc-600 dark:text-zinc-400">
                        {formatDate(filing.filingDate)}
                      </td>
                      <td className="hidden py-2 text-zinc-600 sm:table-cell dark:text-zinc-400">
                        {filing.items
                          ? filing.items
                              .split(",")
                              .map((item) => describeItem(item.trim()))
                              .join("; ")
                          : filing.reportDate && `Period ended ${formatDate(filing.reportDate)}`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Panel>

          <Panel title="Watched companies" action={<PanelLink href="/watchlist">Manage</PanelLink>}>
            <ul className="flex flex-col divide-y divide-zinc-200 dark:divide-zinc-800">
              {data.companies.map((company) => (
                <li
                  key={company.cik}
                  className="-mx-2 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 rounded-lg px-2 py-2.5 transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-900/60"
                >
                  <Link href={`/companies/${company.cik}`} className="flex items-center gap-2.5 font-medium hover:underline">
                    <span
                      aria-hidden
                      className="grid size-8 place-items-center rounded-lg bg-zinc-100 text-[10px] font-bold text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300"
                    >
                      {(company.ticker ?? company.name).slice(0, 4).toUpperCase()}
                    </span>
                    {company.name}
                    {company.ticker && <span className="text-xs font-normal text-zinc-500">{company.ticker}</span>}
                  </Link>
                  <span className="text-xs text-zinc-600 dark:text-zinc-400">
                    {company.latestFiling ? (
                      <Link href={`/filings/${company.latestFiling.accessionNumber}`} className="hover:underline">
                        Latest {company.latestFiling.formType} filed {formatDate(company.latestFiling.filingDate)}
                      </Link>
                    ) : (
                      `No ${company.formTypes.join("/")} filings yet`
                    )}
                    {company.unreadNotifications > 0 && (
                      <span className="ml-2 animate-scale-in rounded-full bg-violet-100 px-1.5 py-0.5 text-violet-800 dark:bg-violet-950 dark:text-violet-300">
                        {company.unreadNotifications} new
                      </span>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          </Panel>
        </div>

        <div className="flex min-w-0 flex-col gap-6">
          <Panel title="Notifications" action={<PanelLink href="/notifications">See all</PanelLink>}>
            {data.notifications.length === 0 ? (
              <Empty>No notifications yet.</Empty>
            ) : (
              <ul className="flex flex-col gap-2.5">
                {data.notifications.map((n) => (
                  <li key={n.id} className="flex gap-2.5 text-sm">
                    <span
                      aria-hidden
                      className={`mt-1.5 size-2 shrink-0 rounded-full ${n.read ? "bg-zinc-300 dark:bg-zinc-700" : "bg-violet-600"}`}
                    />
                    <span className="min-w-0">
                      <Link href={n.link} className={`hover:underline ${n.read ? "" : "font-semibold"}`}>
                        {n.title}
                      </Link>
                      <span className="block text-xs text-zinc-500">{timeAgo(n.createdAt)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Research" action={<PanelLink href="/research">All conversations</PanelLink>}>
            {data.conversations.length === 0 ? (
              <Empty>Open a filing and choose “Ask about this filing” to start a conversation.</Empty>
            ) : (
              <ul className="flex flex-col gap-1 text-sm">
                {data.conversations.map((c) => (
                  <li key={c.id}>
                    <Link
                      href={`/research/${c.id}`}
                      className="-mx-2 flex items-center gap-2 rounded-lg px-2 py-1 transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-900/60"
                    >
                      <Icon name="chat" className="size-3.5 text-zinc-400" />
                      <span className="truncate">{c.title}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Research sessions" action={<PanelLink href="/workspace">Workspace</PanelLink>}>
            {data.sessions.length === 0 ? (
              <Empty>Group saved findings into sessions in your workspace.</Empty>
            ) : (
              <ul className="flex flex-col gap-1 text-sm">
                {data.sessions.map((s) => (
                  <li
                    key={s.id}
                    className="-mx-2 flex items-center justify-between gap-3 rounded-lg px-2 py-1 transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-900/60"
                  >
                    <Link href={`/workspace/sessions/${s.id}`} className="flex min-w-0 items-center gap-2 hover:underline">
                      <Icon name="folder" className="size-3.5 text-zinc-400" />
                      <span className="truncate">{s.title}</span>
                    </Link>
                    <span className="shrink-0 rounded-full bg-zinc-100 px-2 py-0.5 text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400">
                      {s.findingCount} {s.findingCount === 1 ? "finding" : "findings"}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      </div>
    </>
  );
}

function ChangeRow({ change }: { change: RecentChange }) {
  const counts = [
    { label: "added", value: change.added, className: "text-emerald-700 dark:text-emerald-400", bar: "bg-emerald-500" },
    { label: "removed", value: change.removed, className: "text-red-700 dark:text-red-400", bar: "bg-red-500" },
    { label: "modified", value: change.modified, className: "text-amber-700 dark:text-amber-400", bar: "bg-amber-500" },
    { label: "moved", value: change.moved, className: "text-sky-700 dark:text-sky-400", bar: "bg-sky-500" },
  ].filter((c) => c.value > 0);
  const total = counts.reduce((sum, c) => sum + c.value, 0);
  return (
    <Link
      href={`/compare/${change.comparisonId}`}
      className="group -mx-2 flex flex-col gap-1.5 rounded-lg px-2 py-2 transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-900/60"
    >
      <span className="flex items-center justify-between gap-2 text-sm font-medium">
        <span className="group-hover:underline">
          {change.ticker ?? change.companyName} {change.formType}: {formatDate(change.basePeriod)} →{" "}
          {formatDate(change.targetPeriod)}
        </span>
        <Icon
          name="arrowRight"
          className="size-3.5 -translate-x-1 text-zinc-400 opacity-0 transition group-hover:translate-x-0 group-hover:opacity-100"
        />
      </span>
      {total > 0 && (
        <span aria-hidden className="flex h-1.5 gap-px overflow-hidden rounded-full bg-zinc-100 dark:bg-zinc-800">
          {counts.map((c) => (
            <span key={c.label} className={c.bar} style={{ width: `${(c.value / total) * 100}%` }} />
          ))}
        </span>
      )}
      <span className="flex flex-wrap gap-x-3 text-xs">
        {counts.length === 0 ? (
          <span className="text-zinc-500">No text changes</span>
        ) : (
          counts.map((c) => (
            <span key={c.label} className={c.className}>
              {c.value} {c.label}
            </span>
          ))
        )}
      </span>
    </Link>
  );
}

interface StatProps {
  index: number;
  icon: IconName;
  label: string;
  value: number;
  href?: string;
  highlight?: boolean;
}

function Stat({ index, icon, label, value, href, highlight = false }: StatProps) {
  const shown = useCountUp(value);
  const content = (
    <>
      <dt className="flex items-start justify-between gap-2 text-xs text-zinc-500">
        {label}
        <span
          aria-hidden
          className={`grid size-7 shrink-0 place-items-center rounded-lg transition-transform group-hover:scale-110 ${
            highlight
              ? "bg-violet-600 text-white"
              : "bg-zinc-100 text-zinc-500 group-hover:bg-violet-100 group-hover:text-violet-700 dark:bg-zinc-800 dark:group-hover:bg-violet-500/15 dark:group-hover:text-violet-300"
          }`}
        >
          <Icon name={icon} className="size-3.5" />
        </span>
      </dt>
      <dd className="mt-2 text-3xl font-semibold tracking-tight tabular-nums">{shown}</dd>
    </>
  );
  const className =
    "group block h-full animate-fade-in-up rounded-xl border border-zinc-200 bg-white p-4 shadow-xs transition dark:border-zinc-800 dark:bg-zinc-950";
  return href ? (
    <div>
      <Link
        href={href}
        style={stagger(index)}
        className={`${className} hover:-translate-y-0.5 hover:border-violet-300 hover:shadow-md dark:hover:border-violet-500/50`}
      >
        {content}
      </Link>
    </div>
  ) : (
    <div style={stagger(index)} className={className}>
      {content}
    </div>
  );
}

/** Animates a number from its previous value, or from zero on mount, unless the user prefers reduced motion. */
function useCountUp(value: number, duration = 700) {
  const [shown, setShown] = useState(() => (prefersMotion() ? 0 : value));
  useEffect(() => {
    let frame = 0;
    if (!prefersMotion()) {
      frame = requestAnimationFrame(() => setShown(value));
      return () => cancelAnimationFrame(frame);
    }
    let from: number | null = null;
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min((now - start) / duration, 1);
      setShown((current) => {
        from ??= current;
        return Math.round(from + (value - from) * (1 - Math.pow(1 - t, 3)));
      });
      if (t < 1) {
        frame = requestAnimationFrame(tick);
      }
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value, duration]);
  return shown;
}

function prefersMotion() {
  return typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: no-preference)").matches;
}

function stagger(index: number): CSSProperties {
  return { animationDelay: `${index * 60}ms` };
}

function greeting() {
  const hour = new Date().getHours();
  return hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
}

function formBadge(form: string) {
  if (form.startsWith("10-K")) {
    return "bg-indigo-100 text-indigo-800 ring-indigo-300 dark:bg-indigo-500/15 dark:text-indigo-300 dark:ring-indigo-500/40";
  }
  if (form.startsWith("10-Q")) {
    return "bg-sky-100 text-sky-800 ring-sky-300 dark:bg-sky-500/15 dark:text-sky-300 dark:ring-sky-500/40";
  }
  return "bg-amber-100 text-amber-800 ring-amber-300 dark:bg-amber-500/15 dark:text-amber-300 dark:ring-amber-500/40";
}

function DashboardSkeleton() {
  return (
    <div role="status" className="flex flex-col gap-6">
      <span className="sr-only">Loading your dashboard…</span>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-24 rounded-xl" />
        ))}
      </div>
      <div className="grid gap-6 lg:grid-cols-[3fr_2fr]">
        <Skeleton className="h-72 rounded-xl" />
        <Skeleton className="h-72 rounded-xl" />
      </div>
    </div>
  );
}

function Panel({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section
      aria-label={title}
      className="animate-fade-in-up rounded-xl border border-zinc-200 bg-white p-5 shadow-xs dark:border-zinc-800 dark:bg-zinc-950"
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-semibold">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function PanelLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link
      href={href}
      className="group flex items-center gap-1 text-xs font-medium text-violet-700 hover:underline dark:text-violet-400"
    >
      {children}
      <Icon name="arrowRight" className="size-3 transition-transform group-hover:translate-x-0.5" />
    </Link>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="text-sm text-zinc-600 dark:text-zinc-400">{children}</p>;
}
