import { describe, it, expect } from "vitest";
import { consumeSse } from "./api";

function response(text: string) {
  const bytes = new TextEncoder().encode(text);
  return new Response(
    new ReadableStream({
      start(controller) {
        // One-byte chunks intentionally split UTF-8 characters and CRLF delimiters.
        for (let i = 0; i < bytes.length; i++)
          controller.enqueue(bytes.slice(i, i + 1));
        controller.close();
      },
    }),
  );
}

describe("SSE framing", () => {
  it("parses Spring SSE names without spaces and split Unicode", async () => {
    const events: unknown[] = [];
    await consumeSse(
      response(
        'event:token\r\ndata:{"text":"café"}\r\n\r\nevent:done\ndata:{}\n\n',
      ),
      (e) => events.push(e),
    );
    expect(events).toEqual([
      { name: "token", data: { text: "café" } },
      { name: "done", data: {} },
    ]);
  });
  it("rejects a truncated stream", async () => {
    await expect(
      consumeSse(
        response('event:token\ndata:{"text":"partial"}\n\n'),
        () => {},
      ),
    ).rejects.toThrow("before the answer was saved");
  });
  it("surfaces server errors", async () => {
    await expect(
      consumeSse(
        response('event:error\ndata:{"message":"Provider failed"}\n\n'),
        () => {},
      ),
    ).rejects.toThrow("Provider failed");
  });
});
