"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import MarkdownIt from "markdown-it";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useSession } from "@/components/auth";
import { CodeEditor } from "@/components/code-editor";
import { api, ApiError, DIFF_COLOR, STATUS_LABEL, type ProblemDetail, type SubmissionRow, type Verdict } from "@/lib/api";

// raw HTML in the statement is escaped; statements are our own content but the renderer stays safe by default
const md = new MarkdownIt({ html: false, linkify: true });

export default function ProblemPage() {
  const { slug } = useParams<{ slug: string }>();
  const { username } = useSession();
  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [languages, setLanguages] = useState<string[]>(["javascript"]);
  const [runner, setRunner] = useState("");
  const [language, setLanguage] = useState("javascript");
  const [code, setCode] = useState("");
  const [resetKey, setResetKey] = useState("0");
  const [result, setResult] = useState<{ kind: "run" | "submit"; v: Verdict } | null>(null);
  const [busy, setBusy] = useState<"run" | "submit" | null>(null);
  const [error, setError] = useState("");
  const [history, setHistory] = useState<SubmissionRow[]>([]);
  const [tab, setTab] = useState<"result" | "history">("result");

  useEffect(() => {
    Promise.all([api<ProblemDetail>(`/problems/${slug}`), api<{ languages: string[]; runner: string }>("/meta")])
      .then(([p, m]) => { setProblem(p); setLanguages(m.languages); setRunner(m.runner); setLanguage(m.languages[0]); setCode(p.starters[m.languages[0]] ?? ""); setResetKey("init"); })
      .catch((e) => setError(e.message));
  }, [slug]);

  const loadHistory = useCallback(() => {
    if (username) api<SubmissionRow[]>(`/submissions?problem=${slug}`).then(setHistory).catch(() => {});
  }, [slug, username]);
  useEffect(loadHistory, [loadHistory]);

  const statement = useMemo(() => (problem ? md.render(problem.statement) : ""), [problem]);

  async function send(kind: "run" | "submit") {
    if (!problem) return;
    setBusy(kind); setError(""); setTab("result");
    try {
      const v = await api<Verdict>(`/problems/${slug}/${kind}`, { method: "POST", body: { language, code } });
      setResult({ kind, v });
      if (kind === "submit") {
        loadHistory();
        api<ProblemDetail>(`/problems/${slug}`).then(setProblem).catch(() => {});
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setError("Sign in to run or submit code.");
      else setError(e instanceof Error ? e.message : "Something went wrong.");
    } finally {
      setBusy(null);
    }
  }

  if (error && !problem) return <p className="p-6 text-red-600">{error} <Link className="underline" href="/">Back to problems</Link></p>;
  if (!problem) return <p className="p-6 text-sm text-muted">Loading…</p>;

  return (
    <div className="mx-auto grid max-w-7xl gap-4 px-4 py-4 lg:grid-cols-2">
      <section className="min-w-0 space-y-3 lg:max-h-[calc(100vh-70px)] lg:overflow-auto">
        <Link href="/" className="text-sm text-muted hover:underline">← Problems</Link>
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-xl font-semibold">{problem.title}</h1>
          <span className={`text-sm font-medium capitalize ${DIFF_COLOR[problem.difficulty]}`}>{problem.difficulty}</span>
          {problem.solved && <span className="rounded bg-emerald-100 px-1.5 py-0.5 text-xs text-emerald-800">Solved</span>}
        </div>
        <div className="flex flex-wrap gap-1">{problem.topics.map((t) => <span key={t} className="rounded bg-bg px-1.5 py-0.5 text-xs text-muted">{t}</span>)}</div>
        <div className="prose-p text-sm leading-relaxed" dangerouslySetInnerHTML={{ __html: statement }} />
        <h2 className="text-sm font-semibold">Sample tests</h2>
        {problem.samples.map((s, i) => (
          <div key={i} className="rounded-lg border border-line bg-card p-3 text-xs">
            <div className="text-muted">Arguments: <code>{problem.argNames.join(", ")}</code></div>
            <div className="mt-1 font-mono break-all">Input: {s.input.length > 300 ? s.input.slice(0, 300) + "…" : s.input}</div>
            <div className="font-mono break-all">Output: {s.output.length > 300 ? s.output.slice(0, 300) + "…" : s.output}</div>
          </div>
        ))}
        <p className="text-xs text-muted">Implement <code>{problem.functionName}</code> and <b>return</b> the answer (no input or printing). Your submission also runs against hidden tests.</p>
      </section>

      <section className="flex min-w-0 flex-col gap-3">
        <div className="flex items-center gap-2">
          <select className="input !w-auto" aria-label="Language" value={language} onChange={(e) => { setLanguage(e.target.value); setCode(problem.starters[e.target.value] ?? ""); setResetKey(e.target.value); }}>
            {languages.map((l) => <option key={l} value={l}>{l[0].toUpperCase() + l.slice(1)}</option>)}
          </select>
          <button className="btn" onClick={() => { if (confirm("Reset to the starter code?")) { setCode(problem.starters[language] ?? ""); setResetKey(String(Date.now())); } }}>Reset</button>
          <span className="flex-1" />
          <button className="btn" disabled={!!busy || !username} onClick={() => send("run")}>{busy === "run" ? "Running…" : "Run samples"}</button>
          <button className="btn btn-primary" disabled={!!busy || !username} onClick={() => send("submit")}>{busy === "submit" ? "Judging…" : "Submit"}</button>
        </div>
        {!username && <p className="text-sm text-muted"><Link className="underline" href="/login">Sign in</Link> to run and submit your code.</p>}
        <div className="h-[340px]"><CodeEditor value={code} language={language} resetKey={resetKey} onChange={setCode} /></div>
        <p className="text-xs text-muted">Runner: {runner === "local-js" ? "local sandbox (JavaScript only)" : runner}. Runs can take several seconds.</p>

        <div className="rounded-xl border border-line bg-card">
          <div className="flex border-b border-line text-sm">
            <button className={`px-4 py-2 ${tab === "result" ? "border-b-2 border-accent font-medium" : "text-muted"}`} onClick={() => setTab("result")}>Result</button>
            <button className={`px-4 py-2 ${tab === "history" ? "border-b-2 border-accent font-medium" : "text-muted"}`} onClick={() => setTab("history")}>My submissions ({history.length})</button>
          </div>
          <div className="p-4 text-sm">
            {error && <p role="alert" className="text-red-600">{error}</p>}
            {tab === "result" && !error && (result ? <ResultView kind={result.kind} v={result.v} /> : <p className="text-muted">Run the samples to check your idea, or submit to be judged on every test.</p>)}
            {tab === "history" && (history.length === 0 ? <p className="text-muted">No submissions yet.</p> : (
              <ul className="divide-y divide-line">{history.map((h) => (
                <li key={h.id} className="flex items-center gap-3 py-1.5">
                  <span className={h.status === "ACCEPTED" ? "font-medium text-emerald-600" : "text-red-600"}>{STATUS_LABEL[h.status] ?? h.status}</span>
                  <span className="text-muted">{h.passed}/{h.total} · {h.language}</span><span className="flex-1" /><span className="text-xs text-muted">{new Date(h.createdAt).toLocaleString()}</span>
                </li>))}</ul>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

function ResultView({ kind, v }: { kind: "run" | "submit"; v: Verdict }) {
  const ok = v.status === "ACCEPTED";
  return (
    <div className="space-y-2" role="status">
      <div className={`text-lg font-semibold ${ok ? "text-emerald-600" : "text-red-600"}`}>{kind === "run" && ok ? "Samples passed" : STATUS_LABEL[v.status] ?? v.status}</div>
      <div className="text-muted">{v.passed} of {v.total} {kind === "run" ? "sample " : ""}tests passed · {(v.runtimeMs / 1000).toFixed(1)} s</div>
      {v.message && !ok && <pre className="overflow-auto whitespace-pre-wrap rounded bg-bg p-2 text-xs">{v.message}</pre>}
      {v.failure && (v.failure.hidden
        ? <p className="text-muted">Failed hidden test #{v.failure.index}. Hidden tests do not show their data; think about edge cases and larger inputs.</p>
        : <div className="space-y-1 rounded bg-bg p-2 font-mono text-xs break-all">
            <div>Test #{v.failure.index}</div><div>Input: {v.failure.args}</div><div>Expected: {v.failure.expected}</div>
            <div>{v.failure.got != null ? `Got: ${v.failure.got}` : `Error: ${v.failure.message}`}</div>
          </div>)}
      {ok && kind === "submit" && <p className="text-emerald-700">Nice. This problem is now marked solved.</p>}
    </div>
  );
}
