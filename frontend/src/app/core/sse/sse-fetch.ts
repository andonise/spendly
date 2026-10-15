import { Observable } from 'rxjs';

/** One Server-Sent Event as parsed from the wire. */
export interface SseMessage {
  event: string;
  data: string;
  id?: string;
}

/**
 * Streams Server-Sent Events from a POST request with `fetch` + `ReadableStream`
 * (EventSource only supports GET). Intended for chat (`POST /api/chat/stream`) and
 * "regenerate recommendations" in later phases. Unsubscribing aborts the request.
 *
 * Note: this bypasses HttpClient, so neither the error interceptor nor the mock API see it.
 */
export function postEventStream(url: string, body: unknown, init: RequestInit = {}): Observable<SseMessage> {
  return new Observable<SseMessage>((subscriber) => {
    const controller = new AbortController();
    (async () => {
      const response = await fetch(url, {
        ...init,
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...(init.headers ?? {}) },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`Stream request failed: HTTP ${response.status}`);
      }
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      const parser = new SseParser();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        for (const message of parser.push(value)) subscriber.next(message);
      }
      for (const message of parser.flush()) subscriber.next(message);
      subscriber.complete();
    })().catch((error: unknown) => {
      if (!controller.signal.aborted) subscriber.error(error);
    });
    return () => controller.abort();
  });
}

/** Incremental parser for the text/event-stream format (handles chunks split anywhere). */
export class SseParser {
  private buffer = '';
  private event = 'message';
  private data: string[] = [];
  private id: string | undefined;

  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const out: SseMessage[] = [];
    let newline: number;
    while ((newline = this.buffer.search(/\r\n|\r|\n/)) >= 0) {
      const line = this.buffer.slice(0, newline);
      const sepLength = this.buffer.startsWith('\r\n', newline) ? 2 : 1;
      this.buffer = this.buffer.slice(newline + sepLength);
      const message = this.line(line);
      if (message) out.push(message);
    }
    return out;
  }

  flush(): SseMessage[] {
    const message = this.buffer ? this.line(this.buffer) : null;
    this.buffer = '';
    const last = this.line('');
    return [message, last].filter((m): m is SseMessage => m !== null);
  }

  private line(line: string): SseMessage | null {
    if (line === '') {
      if (this.data.length === 0) return null;
      const message: SseMessage = { event: this.event, data: this.data.join('\n'), id: this.id };
      this.event = 'message';
      this.data = [];
      return message;
    }
    if (line.startsWith(':')) return null; // comment, e.g. ": keep-alive"
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
    else if (field === 'id') this.id = value;
    return null;
  }
}
