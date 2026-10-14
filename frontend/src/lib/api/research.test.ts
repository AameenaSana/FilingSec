import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./client";
import { streamQuestion } from "./research";

function streamOf(chunks: string[]) {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
}

describe("streamQuestion", () => {
  afterEach(() => {
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });

  it("parses start, delta and done events even when split across chunks", async () => {
    document.cookie = "XSRF-TOKEN=tok";
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        streamOf([
          'event:start\ndata:{"userMessageId":1,"assistantMessageId":2}\n\nevent:delta\ndata:{"text":"Apple ',
          'cites"}\n\nevent:delta\ndata:{"text":" AI risks."}\n\n',
          'event:done\ndata:{"id":2,"role":"ASSISTANT","content":"Apple cites AI risks.","status":"COMPLETE","createdAt":"2026-01-01T00:00:00Z","citations":[]}\n\n',
        ]),
        { status: 200, headers: { "Content-Type": "text/event-stream" } },
      ),
    );
    const deltas: string[] = [];
    const onStart = vi.fn();
    const onDone = vi.fn();

    await streamQuestion(7, "What AI risks?", { onStart, onDelta: (t) => deltas.push(t), onDone, onError: vi.fn() });

    expect(onStart).toHaveBeenCalledWith({ userMessageId: 1, assistantMessageId: 2 });
    expect(deltas.join("")).toBe("Apple cites AI risks.");
    expect(onDone).toHaveBeenCalledWith(expect.objectContaining({ id: 2, status: "COMPLETE" }));
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/v1/conversations/7/messages");
    expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("tok");
    expect(JSON.parse(String(init?.body))).toEqual({ content: "What AI risks?" });
  });

  it("reports stream errors", async () => {
    document.cookie = "XSRF-TOKEN=tok";
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(streamOf(['event: error\ndata: {"message":"The answer could not be generated."}\n\n']), { status: 200 }),
    );
    const onError = vi.fn();

    await streamQuestion(7, "q", { onDelta: vi.fn(), onDone: vi.fn(), onError });

    expect(onError).toHaveBeenCalledWith("The answer could not be generated.");
  });

  it("throws problem details returned before streaming", async () => {
    document.cookie = "XSRF-TOKEN=tok";
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ type: "urn:filinglens:problem:not-ready", detail: "Still preparing" }), {
        status: 409,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );

    const error = (await streamQuestion(7, "q", { onDelta: vi.fn(), onDone: vi.fn(), onError: vi.fn() }).catch(
      (e: unknown) => e,
    )) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe("not-ready");
  });
});
