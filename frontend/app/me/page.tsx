"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useSession } from "@/components/auth";
import { api, DIFF_COLOR, STATUS_LABEL, type Progress } from "@/lib/api";

export default function Me() {
  const { username, loading } = useSession();
  const [p, setP] = useState<Progress | null>(null);
  useEffect(() => { if (username) api<Progress>("/me/progress").then(setP).catch(() => {}); }, [username]);
  if (loading) return <p className="p-6 text-sm text-muted">Loading…</p>;
  if (!username) return <p className="p-6 text-sm">Please <Link className="underline" href="/login">sign in</Link> to see your progress.</p>;
  if (!p) return <p className="p-6 text-sm text-muted">Loading…</p>;
  return (
    <div className="mx-auto max-w-3xl space-y-5 px-4 py-6">
      <h1 className="text-2xl font-semibold">Your progress</h1>
      <div className="rounded-xl border border-line bg-card p-4">
        <div className="text-3xl font-semibold tabular-nums">{p.solved}<span className="text-lg text-muted"> / {p.total} solved</span></div>
        <div className="mt-3 grid gap-3 sm:grid-cols-3">
          {(["easy", "medium", "hard"] as const).map((d) => {
            const x = p.byDifficulty[d] ?? { solved: 0, total: 0 };
            return (
              <div key={d}>
                <div className={`text-sm font-medium capitalize ${DIFF_COLOR[d]}`}>{d} · {x.solved}/{x.total}</div>
                <div className="mt-1 h-2 overflow-hidden rounded bg-bg"><div className="h-full bg-accent" style={{ width: `${x.total ? (x.solved / x.total) * 100 : 0}%` }} /></div>
              </div>
            );
          })}
        </div>
      </div>
      <section>
        <h2 className="mb-2 text-sm font-semibold">Recent submissions</h2>
        {p.recent.length === 0 ? <p className="rounded-xl border border-dashed border-line p-6 text-center text-sm text-muted">No submissions yet. <Link className="underline" href="/">Pick a problem</Link>.</p> : (
          <ul className="divide-y divide-line rounded-xl border border-line bg-card">
            {p.recent.map((s) => (
              <li key={s.id} className="flex items-center gap-3 px-4 py-2 text-sm">
                <Link className="font-medium hover:underline" href={`/problems/${s.problemSlug}`}>{s.problemTitle}</Link>
                <span className={s.status === "ACCEPTED" ? "text-emerald-600" : "text-red-600"}>{STATUS_LABEL[s.status] ?? s.status}</span>
                <span className="flex-1" /><span className="text-xs text-muted">{new Date(s.createdAt).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
