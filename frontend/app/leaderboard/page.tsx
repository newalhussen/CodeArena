"use client";

import { useEffect, useState } from "react";
import { useSession } from "@/components/auth";
import { api } from "@/lib/api";

interface Row { rank: number; username: string; solved: number; points: number }

export default function Leaderboard() {
  const { username } = useSession();
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState("");
  useEffect(() => { api<Row[]>("/leaderboard").then(setRows).catch((e) => setError(e.message)); }, []);
  return (
    <div className="mx-auto max-w-2xl space-y-4 px-4 py-6">
      <div><h1 className="text-2xl font-semibold">Leaderboard</h1><p className="text-sm text-muted">Points per solved problem: easy 10, medium 20, hard 30. Ties go to whoever finished first.</p></div>
      {error && <p role="alert" className="text-red-600">{error}</p>}
      {rows?.length === 0 && <div className="rounded-xl border border-dashed border-line p-10 text-center text-sm text-muted">Nobody has solved a problem yet. Be the first.</div>}
      {rows && rows.length > 0 && (
        <table className="w-full overflow-hidden rounded-xl border border-line bg-card text-sm">
          <thead className="border-b border-line text-left text-xs uppercase text-muted"><tr><th className="px-4 py-2">#</th><th>User</th><th className="text-right">Solved</th><th className="pr-4 text-right">Points</th></tr></thead>
          <tbody className="divide-y divide-line">
            {rows.map((r) => (
              <tr key={r.username} className={r.username === username ? "bg-bg font-medium" : ""}>
                <td className="px-4 py-2 tabular-nums">{r.rank}</td><td>{r.username}{r.username === username && " (you)"}</td><td className="text-right tabular-nums">{r.solved}</td><td className="pr-4 text-right tabular-nums">{r.points}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
