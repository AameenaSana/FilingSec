"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import { errorMessage } from "@/lib/api/client";
import { companiesApi, FORM_TYPES, queryKeys, type CompanySummary } from "@/lib/api/companies";
import { researchApi } from "@/lib/api/research";

const MAX_COMPANIES = 4;

/**
 * Sets up a cross-company comparison: each selected company's latest filing of the
 * chosen form (optionally for a fiscal year) is compared on a question.
 */
export function CrossCompanyForm() {
  const router = useRouter();
  const [companies, setCompanies] = useState<CompanySummary[]>([]);
  const [formType, setFormType] = useState<string>("10-K");
  const [fiscalYear, setFiscalYear] = useState("");
  const [topic, setTopic] = useState("");
  const [question, setQuestion] = useState("");
  const create = useMutation({
    mutationFn: () =>
      researchApi.createCrossCompany(
        companies.map((c) => c.cik),
        formType,
        fiscalYear ? Number(fiscalYear) : null,
        topic.trim(),
      ),
    onSuccess: (conversation) =>
      router.push(`/research/${conversation.id}?q=${encodeURIComponent(question.trim())}`),
  });

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    create.mutate();
  }

  const valid = companies.length >= 2 && question.trim().length > 0 && (!fiscalYear || /^\d{4}$/.test(fiscalYear));

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-5" noValidate>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">Companies (2–{MAX_COMPANIES})</legend>
        {companies.length > 0 && (
          <ul className="flex flex-wrap gap-2">
            {companies.map((company) => (
              <li key={company.cik} className="flex items-center gap-1 rounded-full border border-zinc-300 py-1 pr-1 pl-3 text-sm dark:border-zinc-700">
                {company.name}
                <button
                  type="button"
                  aria-label={`Remove ${company.name}`}
                  onClick={() => setCompanies((list) => list.filter((c) => c.cik !== company.cik))}
                  className="rounded-full px-2 text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800"
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        )}
        {companies.length < MAX_COMPANIES && (
          <CompanyPicker
            exclude={companies.map((c) => c.cik)}
            onPick={(company) => setCompanies((list) => [...list, company])}
          />
        )}
      </fieldset>

      <div className="flex flex-wrap gap-4">
        <div className="flex flex-col gap-1.5">
          <label htmlFor="form-type" className="text-sm font-medium">
            Form
          </label>
          <select
            id="form-type"
            value={formType}
            onChange={(e) => setFormType(e.target.value)}
            className="h-10 rounded-md border border-zinc-300 bg-white px-3 text-sm dark:border-zinc-700 dark:bg-zinc-900"
          >
            {FORM_TYPES.map((form) => (
              <option key={form}>{form}</option>
            ))}
          </select>
        </div>
        <TextField
          label="Fiscal year (optional)"
          inputMode="numeric"
          placeholder="Latest"
          value={fiscalYear}
          onChange={(e) => setFiscalYear(e.target.value.replace(/\D/g, "").slice(0, 4))}
          hint="Year the reporting period ended"
        />
      </div>

      <TextField
        label="Topic (optional)"
        placeholder="e.g. AI-related risks"
        maxLength={120}
        value={topic}
        onChange={(e) => setTopic(e.target.value)}
      />
      <div className="flex flex-col gap-1.5">
        <label htmlFor="cross-question" className="text-sm font-medium">
          Question
        </label>
        <textarea
          id="cross-question"
          rows={3}
          maxLength={2000}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="e.g. How does each company describe risks related to artificial intelligence?"
          className="rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm dark:border-zinc-700 dark:bg-zinc-900"
        />
      </div>

      {create.error && <Alert tone="error">{errorMessage(create.error)}</Alert>}
      <div>
        <Button type="submit" pending={create.isPending} disabled={!valid}>
          Compare
        </Button>
      </div>
    </form>
  );
}

function CompanyPicker({ exclude, onPick }: { exclude: string[]; onPick: (company: CompanySummary) => void }) {
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setQuery(input.trim()), 250);
    return () => clearTimeout(timer);
  }, [input]);
  const { data } = useQuery({
    queryKey: queryKeys.companySearch(query),
    queryFn: () => companiesApi.search(query, 6),
    enabled: query.length > 0,
  });
  const results = (data ?? []).filter((c) => !exclude.includes(c.cik));

  return (
    <div className="flex flex-col gap-1">
      <label htmlFor="company-picker" className="sr-only">
        Add a company
      </label>
      <input
        id="company-picker"
        type="search"
        autoComplete="off"
        placeholder="Add a company by name, ticker or CIK"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        className="h-10 rounded-md border border-zinc-300 bg-white px-3 text-sm dark:border-zinc-700 dark:bg-zinc-900"
      />
      {query && results.length > 0 && (
        <ul aria-label="Matching companies" className="rounded-md border border-zinc-200 dark:border-zinc-800">
          {results.map((company) => (
            <li key={company.cik}>
              <button
                type="button"
                onClick={() => {
                  onPick(company);
                  setInput("");
                  setQuery("");
                }}
                className="w-full px-3 py-2 text-left text-sm hover:bg-zinc-50 dark:hover:bg-zinc-900"
              >
                {company.name}
                <span className="ml-2 text-xs text-zinc-500">{company.tickers.join(", ")}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
