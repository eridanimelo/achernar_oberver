export interface Span {
  id: string;
  traceId: string;
  project?: string;
  parentSpanId?: string;
  sessionId?: string;
  name?: string;
  model?: string;
  agent?: string;
  kind?: string;
  startedAt: string;
  durationMs: number;
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  totalTokens?: number;
  status?: number;
  error?: string;
  metadata?: Record<string, unknown>;
  requestHeaders?: Record<string, unknown>;
  responseHeaders?: Record<string, unknown>;
  requestBody?: unknown;
  responseBody?: unknown;
  children?: Span[];
}

export interface SpanPage {
  content: Span[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
