export interface ProblemSummary { slug: string; title: string; difficulty: "easy" | "medium" | "hard"; topics: string[]; solved: boolean; attempted: boolean }
export interface ProblemDetail {
  slug: string; title: string; difficulty: "easy" | "medium" | "hard"; topics: string[]; statement: string; functionName: string; argNames: string[];
  samples: { input: string; output: string }[]; starters: Record<string, string>; solved: boolean;
}
export interface Failure { index: number; hidden: boolean; args: string | null; expected: string | null; got: string | null; message: string | null }
export interface Verdict { id?: number; status: string; passed: number; total: number; message: string; runtimeMs: number; failure: Failure | null }
export interface SubmissionRow { id: number; problemSlug: string; problemTitle: string; language: string; status: string; passed: number; total: number; message: string; runtimeMs: number; createdAt: string }
export interface Progress { username: string; solved: number; total: number; byDifficulty: Record<string, { solved: number; total: number }>; recent: SubmissionRow[] }

export class ApiError extends Error {
  constructor(message: string, public status: number, public fields: Record<string, string> = {}) {
    super(message);
  }
}

export async function api<T>(path: string, init?: { method?: string; body?: unknown }): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method: init?.method ?? "GET",
    headers: init?.body !== undefined ? { "content-type": "application/json" } : undefined,
    body: init?.body !== undefined ? JSON.stringify(init.body) : undefined,
    cache: "no-store",
  }).catch(() => { throw new ApiError("Cannot reach the server.", 0); });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error ?? `Request failed (${res.status})`, res.status, data.fields ?? {});
  return data as T;
}

export const STATUS_LABEL: Record<string, string> = {
  ACCEPTED: "Accepted", WRONG_ANSWER: "Wrong answer", RUNTIME_ERROR: "Runtime error", COMPILE_ERROR: "Compile error",
  TIME_LIMIT_EXCEEDED: "Time limit exceeded", MEMORY_LIMIT_EXCEEDED: "Memory limit exceeded", INTERNAL_ERROR: "Runner error",
};

export const DIFF_COLOR = { easy: "text-emerald-600", medium: "text-amber-600", hard: "text-red-600" } as const;
