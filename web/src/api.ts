const BASE = import.meta.env.VITE_API_URL ?? "";

export async function request<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const response = await fetch(BASE + "/api" + path, options);
  if (!response.ok) {
    const text = await response.text();
    let message = text || `Request failed (${response.status})`;
    try {
      message = JSON.parse(text).error ?? message;
    } catch {
      /* Non-JSON proxy errors. */
    }
    throw new Error(message);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const json = (body: unknown): RequestInit => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export type Event = { name: string; data: unknown };

/** A streaming parser: chunks need not line up with lines, UTF-8 characters or events. */
export async function consumeSse(
  response: Response,
  onEvent: (event: Event) => void,
) {
  if (!response.ok || !response.body)
    throw new Error((await response.text()) || "Stream unavailable");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let pending = "",
    completed = false;
  const frame = (value: string) => {
    let name = "message";
    const data: string[] = [];
    for (const line of value.split(/\r?\n/)) {
      const colon = line.indexOf(":");
      if (colon < 0) continue;
      const key = line.slice(0, colon),
        content = line.slice(colon + 1).replace(/^ /, "");
      if (key === "event") name = content;
      if (key === "data") data.push(content);
    }
    if (!data.length) return;
    const payload = JSON.parse(data.join("\n"));
    if (name === "error")
      throw new Error(payload.message ?? "Answer generation failed");
    if (name === "done") completed = true;
    onEvent({ name, data: payload });
  };
  try {
    while (true) {
      const { done, value } = await reader.read();
      pending += done
        ? decoder.decode()
        : decoder.decode(value, { stream: true });
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/.exec(pending))) {
        frame(pending.slice(0, boundary.index));
        pending = pending.slice(boundary.index + boundary[0].length);
      }
      if (done) break;
    }
    if (pending.trim()) frame(pending);
    if (!completed)
      throw new Error(
        "Stream ended before the answer was saved. Please retry.",
      );
  } finally {
    await reader.cancel();
    reader.releaseLock();
  }
}

export function askStream(
  question: string,
  topK: number,
  promptVariant: string,
  onEvent: (event: Event) => void,
) {
  return fetch(
    BASE + "/api/ask/stream",
    json({ question, topK, promptVariant }),
  ).then((response) => consumeSse(response, onEvent));
}
