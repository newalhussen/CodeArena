"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";

interface Session { username: string | null; loading: boolean; refresh: () => Promise<void> }
const Ctx = createContext<Session>({ username: null, loading: true, refresh: async () => {} });
export const useSession = () => useContext(Ctx);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [username, setUsername] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const refresh = useCallback(async () => {
    try {
      const r = await api<{ user: { username: string } | null }>("/auth/me");
      setUsername(r.user?.username ?? null);
    } catch {
      setUsername(null);
    }
    setLoading(false);
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  return <Ctx.Provider value={{ username, loading, refresh }}>{children}</Ctx.Provider>;
}

export function Nav() {
  const { username, loading, refresh } = useSession();
  const router = useRouter();
  return (
    <header className="border-b border-line bg-card">
      <div className="mx-auto flex max-w-6xl items-center gap-4 px-4 py-2.5">
        <Link href="/" className="flex items-center gap-2 font-semibold">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m8 8-5 4 5 4M16 8l5 4-5 4M14 5l-4 14" /></svg>
          CodeArena
        </Link>
        <Link href="/" className="text-sm text-muted hover:text-ink">Problems</Link>
        <Link href="/leaderboard" className="text-sm text-muted hover:text-ink">Leaderboard</Link>
        {username && <Link href="/me" className="text-sm text-muted hover:text-ink">My progress</Link>}
        <span className="flex-1" />
        {!loading && (username ? (
          <>
            <span className="text-sm text-muted">{username}</span>
            <button className="btn" onClick={async () => { await api("/auth/logout", { method: "POST", body: {} }); await refresh(); router.push("/"); }}>Sign out</button>
          </>
        ) : (
          <><Link className="btn" href="/login">Sign in</Link><Link className="btn btn-primary" href="/register">Register</Link></>
        ))}
      </div>
    </header>
  );
}

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const router = useRouter();
  const { refresh } = useSession();
  const [f, setF] = useState({ username: "", password: "" });
  const [error, setError] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setError(""); setFields({});
    try {
      await api(`/auth/${mode}`, { method: "POST", body: f });
      await refresh();
      router.push("/");
    } catch (err) {
      if (err instanceof ApiError) { setError(err.message); setFields(err.fields); }
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="mx-auto mt-12 max-w-sm space-y-4 rounded-xl border border-line bg-card p-6" noValidate>
      <h1 className="text-lg font-semibold">{mode === "login" ? "Sign in" : "Create your account"}</h1>
      <label className="block text-sm">Username{mode === "register" && <span className="text-muted"> (3-20: a-z, 0-9, _)</span>}
        <input className="input mt-1" value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })} autoComplete="username" autoCapitalize="none" />
        {fields.username && <span className="text-xs text-red-600">{fields.username}</span>}
      </label>
      <label className="block text-sm">Password{mode === "register" && <span className="text-muted"> (10+ characters)</span>}
        <input className="input mt-1" type="password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} autoComplete={mode === "login" ? "current-password" : "new-password"} />
        {fields.password && <span className="text-xs text-red-600">{fields.password}</span>}
      </label>
      {error && !Object.keys(fields).length && <p role="alert" className="text-sm text-red-600">{error}</p>}
      <button className="btn btn-primary w-full justify-center" disabled={busy}>{busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create account"}</button>
      <p className="text-center text-sm text-muted">{mode === "login" ? <>New here? <Link className="underline" href="/register">Register</Link></> : <>Have an account? <Link className="underline" href="/login">Sign in</Link></>}</p>
    </form>
  );
}
