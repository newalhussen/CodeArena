"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useSession } from "@/components/auth";
import { api, DIFF_COLOR, type ProblemSummary } from "@/lib/api";

const DIFFS = ["easy", "medium", "hard"] as const;

export default function Problems() {
  const { username, loading } = useSession();
  const [difficulty, setDifficulty] = useState<string | null>(null);
  const [topic, setTopic] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [topics, setTopics] = useState<string[]>([]);
  const [list, setList] = useState<ProblemSummary[] | null>(null);
  const [error, setError] = useState("");

  useEffect(() => { api<{ topics: string[] }>("/meta").then((m) => setTopics(m.topics)).catch(() => {}); }, []);
  useEffect(() => {
    if (loading) return;
    const p = new URLSearchParams();
    if (difficulty) p.set("difficulty", difficulty);
    if (topic) p.set("topic", topic);
    if (q.trim()) p.set("q", q.trim());
    const t = setTimeout(() => api<ProblemSummary[]>(`/problems?${p}`).then((l) => { setList(l); setError(""); }).catch((e) => setError(e.message)), q ? 250 : 0);
    return () => clearTimeout(t);
  }, [difficulty, topic, q, loading, username]);

  const chip = (label: string, active: boolean, on: () => void) => <button key={label} className="chip" aria-current={active} onClick={on}>{label}</button>;

  return (
    <div className="mx-auto max-w-5xl space-y-4 px-4 py-6">
      <div>
        <h1 className="text-2xl font-semibold">Problems</h1>
        <p className="text-sm text-muted">Pick a problem, write a function, and submit it for automated testing.</p>
      </div>
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="w-20 text-sm text-muted">Difficulty</span>
          {chip("All", difficulty === null, () => setDifficulty(null))}
          {DIFFS.map((d) => chip(d[0].toUpperCase() + d.slice(1), difficulty === d, () => setDifficulty(d)))}
          <input className="input !ml-auto !w-56" placeholder="Search problems" aria-label="Search problems" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="w-20 text-sm text-muted">Topic</span>
          {chip("All", topic === null, () => setTopic(null))}
          {topics.map((t) => chip(t, topic === t, () => setTopic(t)))}
        </div>
      </div>
      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      {list === null && !error && <p className="text-sm text-muted">Loading…</p>}
      {list?.length === 0 && <div className="rounded-xl border border-dashed border-line p-10 text-center text-sm text-muted">No problems match these filters.</div>}
      {list && list.length > 0 && (
        <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-card">
          {list.map((p) => (
            <li key={p.slug}>
              <Link href={`/problems/${p.slug}`} className="flex items-center gap-3 px-4 py-3 hover:bg-bg">
                <span className="w-5 text-center" title={p.solved ? "Solved" : p.attempted ? "Attempted" : "Not started"}>{p.solved ? "✓" : p.attempted ? "•" : ""}</span>
                <span className="font-medium">{p.title}</span>
                <span className="flex flex-wrap gap-1">{p.topics.map((t) => <span key={t} className="rounded bg-bg px-1.5 py-0.5 text-xs text-muted">{t}</span>)}</span>
                <span className="flex-1" />
                <span className={`text-sm font-medium capitalize ${DIFF_COLOR[p.difficulty]}`}>{p.difficulty}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
