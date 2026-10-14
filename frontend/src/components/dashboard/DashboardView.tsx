"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import type { ReactNode } from "react";
import { Alert } from "@/components/ui/Alert";
import { errorMessage } from "@/lib/api/client";
import { dashboardApi, dashboardKeys, type Dashboard, type RecentChange } from "@/lib/api/dashboard";
import { describeItem, formatDate } from "@/lib/api/filings";
import { timeAgo } from "@/lib/api/notifications";
import { useAuth } from "@/lib/auth/AuthProvider";

/** Overview of watched companies, their new filings and changes, notifications and research. */
export function DashboardView() {
  const { state } = useAuth();
  const { data, error, isPending } = useQuery({
    queryKey: dashboardKeys.all,
    queryFn: dashboardApi.get,
    refetchInterval: 120_000,
  });

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Welcome, {state.user?.displayName}</h1>
      {isPending && (
        <p role="status" className="text-sm text-zinc-500">
          Loading your dashboard…
        </p>
      )}
      {error && <Alert tone="error">{errorMessage(error)}</Alert>}
      {data && (data.companies.length === 0 ? <GettingStarted /> : <Overview data={data} />)}
    </div>
  );
}

function GettingStarted() {
  return (
    <Panel title="Get started">
      <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm text-zinc-700 dark:text-zinc-300">
        <li>
          <Link href="/companies" className="font-medium underline underline-offset-4">
            Search for companies
          </Link>{" "}
          and add them to your watchlist.
        </li>
        <li>FilingLens checks SEC for their new 10-K, 10-Q and 8-K filings and notifies you.</li>
        <li>New annual and quarterly reports are compared with the previous period automatically.</li>
        <li>Ask questions about any filing and save the cited passages to your workspace.</li>
      </ol>
    </Panel>
  );
}

function Overview({ data }: { data: Dashboard }) {
  const { summary } = data;
  return (
    <>
      <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Watched companies" value={summary.watchedCompanies} href="/watchlist" />
        <Stat label="Filings in the last 30 days" value={summary.filingsLast30Days} />
        <Stat label="Unread notifications" value={summary.unreadNotifications} href="/notifications" />
        <Stat label="Saved findings" value={summary.findings} href="/workspace" />
      </dl>
      <p className="-mt-3 text-xs text-zinc-500">
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
                  <li key={change.comparisonId} className="py-2.5">
                    <ChangeRow change={change} />
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Recent filings" action={<span className="text-xs text-zinc-500">Last 90 days</span>}>
            {data.recentFilings.length === 0 ? (
              <Empty>No filings of the forms you follow in the last 90 days.</Empty>
            ) : (
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-zinc-500">
                  <tr>
                    <th className="pb-2 font-medium">Company</th>
                    <th className="pb-2 font-medium">Form</th>
                    <th className="pb-2 font-medium">Filed</th>
                    <th className="hidden pb-2 font-medium sm:table-cell">Period / items</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
                  {data.recentFilings.map((filing) => (
                    <tr key={filing.accessionNumber}>
                      <td className="py-2 pr-3">
                        <Link href={`/companies/${filing.cik}`} className="hover:underline">
                          {filing.ticker ?? filing.companyName}
                        </Link>
                      </td>
                      <td className="py-2 pr-3">
                        <Link href={`/filings/${filing.accessionNumber}`} className="font-medium hover:underline">
                          {filing.formType}
                        </Link>
                      </td>
                      <td className="py-2 pr-3 whitespace-nowrap">{formatDate(filing.filingDate)}</td>
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
                <li key={company.cik} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5">
                  <Link href={`/companies/${company.cik}`} className="font-medium hover:underline">
                    {company.name}
                    {company.ticker && <span className="ml-1.5 text-xs text-zinc-500">{company.ticker}</span>}
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
                      <span className="ml-2 rounded-full bg-violet-100 px-1.5 py-0.5 text-violet-800 dark:bg-violet-950 dark:text-violet-300">
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
                  <li key={n.id} className="text-sm">
                    <Link href={n.link} className={`hover:underline ${n.read ? "" : "font-semibold"}`}>
                      {n.title}
                    </Link>
                    <span className="block text-xs text-zinc-500">{timeAgo(n.createdAt)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Research" action={<PanelLink href="/research">All conversations</PanelLink>}>
            {data.conversations.length === 0 ? (
              <Empty>Open a filing and choose “Ask about this filing” to start a conversation.</Empty>
            ) : (
              <ul className="flex flex-col gap-2 text-sm">
                {data.conversations.map((c) => (
                  <li key={c.id}>
                    <Link href={`/research/${c.id}`} className="hover:underline">
                      {c.title}
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
              <ul className="flex flex-col gap-2 text-sm">
                {data.sessions.map((s) => (
                  <li key={s.id} className="flex justify-between gap-3">
                    <Link href={`/workspace/sessions/${s.id}`} className="truncate hover:underline">
                      {s.title}
                    </Link>
                    <span className="shrink-0 text-xs text-zinc-500">
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
    { label: "added", value: change.added, className: "text-emerald-700 dark:text-emerald-400" },
    { label: "removed", value: change.removed, className: "text-red-700 dark:text-red-400" },
    { label: "modified", value: change.modified, className: "text-amber-700 dark:text-amber-400" },
    { label: "moved", value: change.moved, className: "text-sky-700 dark:text-sky-400" },
  ].filter((c) => c.value > 0);
  return (
    <Link href={`/compare/${change.comparisonId}`} className="group flex flex-col gap-0.5">
      <span className="text-sm font-medium group-hover:underline">
        {change.ticker ?? change.companyName} {change.formType}: {formatDate(change.basePeriod)} →{" "}
        {formatDate(change.targetPeriod)}
      </span>
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

function Stat({ label, value, href }: { label: string; value: number; href?: string }) {
  const content = (
    <>
      <dt className="text-xs text-zinc-500">{label}</dt>
      <dd className="mt-1 text-2xl font-semibold tabular-nums">{value}</dd>
    </>
  );
  const className = "block rounded-lg border border-zinc-200 p-4 dark:border-zinc-800";
  return href ? (
    <div>
      <Link href={href} className={`${className} hover:bg-zinc-50 dark:hover:bg-zinc-900`}>
        {content}
      </Link>
    </div>
  ) : (
    <div className={className}>{content}</div>
  );
}

function Panel({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-lg border border-zinc-200 p-5 dark:border-zinc-800">
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="font-semibold">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function PanelLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className="text-xs text-violet-700 hover:underline dark:text-violet-400">
      {children}
    </Link>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="text-sm text-zinc-600 dark:text-zinc-400">{children}</p>;
}
