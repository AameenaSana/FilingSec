"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { ApiError, errorMessage } from "@/lib/api/client";
import { formatDate } from "@/lib/api/filings";
import { researchApi, researchKeys, streamQuestion, type Conversation, type ResearchMessage } from "@/lib/api/research";
import { AnswerText } from "./AnswerText";

const POLL_MS = 3000;

interface Pending {
  question: string;
  answer: string;
}

export function ConversationView({ id, initialQuestion }: { id: number; initialQuestion?: string }) {
  const queryClient = useQueryClient();
  const { data: conversation, error, isPending } = useQuery({
    queryKey: researchKeys.detail(id),
    queryFn: () => researchApi.get(id),
    refetchInterval: (query) =>
      query.state.data?.readiness.some((r) => r.status === "PREPARING") ? POLL_MS : false,
  });
  const [question, setQuestion] = useState(initialQuestion ?? "");
  const autoAsked = useRef(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [askError, setAskError] = useState<string | null>(null);

  if (isPending) {
    return <p role="status" className="text-sm text-zinc-500">Loading conversation…</p>;
  }
  if (error) {
    return (
      <Alert tone="error">
        {error instanceof ApiError && error.status === 404 ? "This conversation was not found." : errorMessage(error)}
      </Alert>
    );
  }

  const preparing = conversation.readiness.some((r) => r.status === "PREPARING");
  const unavailable = conversation.readiness.some((r) => r.status === "UNAVAILABLE");
  const canAsk = conversation.aiAvailable && !preparing && !unavailable && pending === null;

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void ask(question.trim());
  }

  async function ask(text: string) {
    if (!text) {
      return;
    }
    setAskError(null);
    setPending({ question: text, answer: "" });
    setQuestion("");
    try {
      await streamQuestion(id, text, {
        onDelta: (delta) => setPending((p) => (p ? { ...p, answer: p.answer + delta } : p)),
        onDone: () => undefined,
        onError: (message) => setAskError(message),
      });
    } catch (e) {
      setAskError(errorMessage(e));
      setQuestion(text);
    } finally {
      await queryClient.invalidateQueries({ queryKey: researchKeys.detail(id) });
      setPending(null);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <Header conversation={conversation} />
      {!conversation.aiAvailable && (
        <Alert>Question answering is not configured on this server. An administrator needs to add an LLM API key.</Alert>
      )}
      {preparing && (
        <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
          Preparing the filings for questions (downloading, reading and indexing)…
        </p>
      )}
      {unavailable && <Alert tone="error">A filing in this conversation could not be read, so questions cannot be answered.</Alert>}

      <ol className="flex flex-col gap-5">
        {conversation.messages.map((message) => (
          <li key={message.id}>
            <MessageItem message={message} />
          </li>
        ))}
        {pending && (
          <>
            <li>
              <UserBubble text={pending.question} />
            </li>
            <li aria-live="polite">
              <AssistantCard>
                <p className="leading-7 whitespace-pre-wrap">{pending.answer || "Searching the filings…"}</p>
              </AssistantCard>
            </li>
          </>
        )}
      </ol>

      {askError && <Alert tone="error">{askError}</Alert>}

      <AutoAsk
        enabled={!!initialQuestion && canAsk && conversation.messages.length === 0 && !autoAsked.current}
        onReady={() => {
          autoAsked.current = true;
          void ask(initialQuestion!.trim());
        }}
      />
      <form onSubmit={onSubmit} className="flex flex-col gap-2">
        <label htmlFor="question" className="sr-only">
          Question
        </label>
        <textarea
          id="question"
          rows={3}
          maxLength={2000}
          value={question}
          disabled={!canAsk}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="Ask about these filings, e.g. “What risks related to artificial intelligence are described?”"
          className="rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm outline-none focus:border-zinc-500 focus:ring-2 focus:ring-zinc-200 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:ring-zinc-800"
        />
        <div className="flex items-center justify-between gap-3">
          <p className="text-xs text-zinc-500">
            Answers are AI-generated from the cited filing passages only. Check the sources before relying on them.
          </p>
          <Button type="submit" pending={pending !== null} disabled={!canAsk || !question.trim()}>
            Ask
          </Button>
        </div>
      </form>
    </div>
  );
}

/** Fires once when enabled, e.g. to ask a pre-filled question when the filings become ready. */
function AutoAsk({ enabled, onReady }: { enabled: boolean; onReady: () => void }) {
  useEffect(() => {
    if (enabled) {
      onReady();
    }
  }, [enabled, onReady]);
  return null;
}

function Header({ conversation }: { conversation: Conversation }) {
  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">{conversation.title}</h1>
      <ul className="mt-2 flex flex-wrap gap-2 text-sm">
        {conversation.filings.map((filing) => (
          <li key={filing.accessionNumber}>
            <Link
              href={`/filings/${filing.accessionNumber}`}
              className="inline-block rounded-full border border-zinc-300 px-3 py-1 hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-900"
            >
              {filing.companyName} {filing.formType}
              {filing.reportDate ? ` · ${formatDate(filing.reportDate)}` : ""}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

function MessageItem({ message }: { message: ResearchMessage }) {
  if (message.role === "USER") {
    return <UserBubble text={message.content} />;
  }
  return (
    <AssistantCard>
      {message.status === "COMPLETE" && (
        <AnswerText messageId={message.id} content={message.content} citations={message.citations} />
      )}
      {message.status === "REFUSED" && <p className="text-zinc-600 dark:text-zinc-400">{message.content}</p>}
      {message.status === "FAILED" && <p className="text-red-700 dark:text-red-400">The answer could not be generated.</p>}
      {message.status === "STREAMING" && <p className="text-zinc-500">Answer in progress…</p>}
    </AssistantCard>
  );
}

function UserBubble({ text }: { text: string }) {
  return (
    <div className="ml-auto max-w-[80%] rounded-lg bg-zinc-100 px-4 py-2 whitespace-pre-wrap dark:bg-zinc-800">{text}</div>
  );
}

function AssistantCard({ children }: { children: React.ReactNode }) {
  return (
    <article className="rounded-lg border border-zinc-200 p-4 text-sm dark:border-zinc-800">
      <p className="mb-2 text-xs font-medium tracking-wide text-zinc-500 uppercase">AI answer</p>
      {children}
    </article>
  );
}
