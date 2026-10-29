"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Icon, Spinner, type IconName } from "@/components/ui/Icon";
import { companiesApi, queryKeys } from "@/lib/api/companies";
import { useDebounced } from "@/lib/useDebounced";
import { NAV } from "./nav";

interface Command {
  id: string;
  label: string;
  hint?: string;
  icon: IconName;
  href: string;
  group: "Companies" | "Go to";
}

const PAGES: Command[] = [
  ...NAV,
  { href: "/notifications", label: "Notifications", icon: "bell" as const },
  { href: "/research/compare-companies", label: "Compare companies", icon: "diff" as const },
].map((page) => ({ id: page.href, label: page.label, icon: page.icon, href: page.href, group: "Go to" }));

const COMPANY_RESULTS = 6;

/** Header search button plus a ⌘K / Ctrl+K palette to jump to pages and companies. */
export function CommandPalette() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      const typing = target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName);
      if ((event.key === "k" && (event.metaKey || event.ctrlKey)) || (event.key === "/" && !typing)) {
        event.preventDefault();
        setOpen((o) => !o);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-haspopup="dialog"
        className="group flex h-9 items-center gap-2 rounded-lg border border-zinc-200 bg-zinc-50 px-3 text-sm text-zinc-500 transition hover:border-zinc-300 hover:bg-white hover:text-zinc-700 dark:border-zinc-800 dark:bg-zinc-900 dark:hover:border-zinc-700 dark:hover:bg-zinc-800 dark:hover:text-zinc-300"
      >
        <Icon name="search" />
        <span className="hidden lg:inline">Search…</span>
        <kbd className="hidden rounded border border-zinc-200 bg-white px-1.5 font-sans text-[10px] font-medium text-zinc-500 lg:inline dark:border-zinc-700 dark:bg-zinc-800">
          Ctrl K
        </kbd>
        <span className="sr-only lg:hidden">Search</span>
      </button>
      {/* Portalled: the header's backdrop blur would otherwise contain the fixed overlay. */}
      {open && createPortal(<Palette onClose={() => setOpen(false)} />, document.body)}
    </>
  );
}

function Palette({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const listId = useId();
  const [input, setInput] = useState("");
  const [active, setActive] = useState(0);
  const list = useRef<HTMLUListElement>(null);
  const query = useDebounced(input.trim(), 200);

  const { data: companies, isFetching } = useQuery({
    queryKey: queryKeys.companySearch(query),
    queryFn: () => companiesApi.search(query),
    enabled: query.length > 0,
    placeholderData: (previous) => previous,
  });

  const needle = input.trim().toLowerCase();
  const commands: Command[] = [
    ...(needle
      ? (companies ?? []).slice(0, COMPANY_RESULTS).map(
          (c): Command => ({
            id: c.cik,
            label: c.name,
            hint: [c.tickers.join(", "), `CIK ${c.cik}`].filter(Boolean).join(" · "),
            icon: "building",
            href: `/companies/${c.cik}`,
            group: "Companies",
          }),
        )
      : []),
    ...PAGES.filter((p) => p.label.toLowerCase().includes(needle)),
  ];
  const activeIndex = Math.min(active, Math.max(commands.length - 1, 0));

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus();
    };
  }, []);

  useEffect(() => {
    list.current?.querySelector(`[data-index="${activeIndex}"]`)?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  function go(command: Command | undefined) {
    if (command) {
      onClose();
      router.push(command.href);
    }
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive((activeIndex + step + commands.length) % Math.max(commands.length, 1));
    } else if (event.key === "Enter") {
      event.preventDefault();
      go(commands[activeIndex]);
    } else if (event.key === "Escape") {
      onClose();
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center px-4 pt-[12vh]">
      <div aria-hidden onClick={onClose} className="absolute inset-0 animate-fade-in bg-zinc-950/40 backdrop-blur-sm" />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Search FilingLens"
        className="relative w-full max-w-xl animate-scale-in overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-2xl dark:border-zinc-800 dark:bg-zinc-950"
      >
        <div className="flex items-center gap-3 border-b border-zinc-200 px-4 dark:border-zinc-800">
          {isFetching ? <Spinner className="size-4 text-violet-500" /> : <Icon name="search" className="size-4 text-zinc-400" />}
          <input
            autoFocus
            role="combobox"
            aria-expanded="true"
            aria-controls={listId}
            aria-activedescendant={commands[activeIndex] ? `${listId}-${activeIndex}` : undefined}
            aria-label="Search companies and pages"
            placeholder="Search companies or jump to a page…"
            value={input}
            maxLength={100}
            onChange={(e) => {
              setInput(e.target.value);
              setActive(0);
            }}
            onKeyDown={onKeyDown}
            className="h-12 flex-1 bg-transparent text-sm outline-none placeholder:text-zinc-400"
          />
          <kbd className="rounded border border-zinc-200 px-1.5 text-[10px] text-zinc-500 dark:border-zinc-700">Esc</kbd>
        </div>
        <ul ref={list} id={listId} role="listbox" aria-label="Results" className="max-h-80 overflow-y-auto p-2">
          {commands.length === 0 && (
            <li className="px-3 py-8 text-center text-sm text-zinc-500">
              {isFetching ? "Searching…" : `No results for “${input.trim()}”`}
            </li>
          )}
          {commands.map((command, index) => (
            <li key={`${command.group}-${command.id}`} role="presentation">
              {(index === 0 || commands[index - 1].group !== command.group) && (
                <p className="px-3 pt-2 pb-1 text-[11px] font-medium tracking-wide text-zinc-400 uppercase">{command.group}</p>
              )}
              <div
                id={`${listId}-${index}`}
                data-index={index}
                role="option"
                aria-selected={index === activeIndex}
                onMouseMove={() => index !== activeIndex && setActive(index)}
                onClick={() => go(command)}
                className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2 text-sm text-zinc-700 aria-selected:bg-violet-50 aria-selected:text-violet-900 dark:text-zinc-300 dark:aria-selected:bg-violet-500/15 dark:aria-selected:text-violet-100"
              >
                <Icon name={command.icon} className="size-4 opacity-70" />
                <span className="min-w-0 flex-1 truncate">
                  {command.label}
                  {command.hint && <span className="ml-2 text-xs text-zinc-500">{command.hint}</span>}
                </span>
                {index === activeIndex && <Icon name="arrowRight" className="size-3.5 opacity-60" />}
              </div>
            </li>
          ))}
        </ul>
        <div className="flex gap-4 border-t border-zinc-200 px-4 py-2 text-[11px] text-zinc-500 dark:border-zinc-800">
          <span>↑↓ navigate</span>
          <span>↵ open</span>
          <span>/ or Ctrl K to toggle</span>
        </div>
      </div>
    </div>
  );
}
