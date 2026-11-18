import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { resolveBase, useSession } from "../lib/session";
import {
  accountId,
  normalizeServer,
  Xtream,
  XtreamError,
  type Account,
  type ConnectionMode,
} from "../lib/xtream";
import { Icon } from "../components/ui";
import { FamilySignIn } from "../components/FamilySignIn";
import { ConnectionDebug } from "../components/ConnectionDebug";
import { diagnose, type Diagnosis } from "../lib/diagnose";
import { DEFAULT_API, useCloud } from "../lib/cloud";

export default function Setup() {
  const { proxy, saveAccount, accounts } = useSession();
  const nav = useNavigate();
  const [server, setServer] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [mode, setMode] = useState<ConnectionMode>("auto");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cloud = useCloud();
  // Open by default when this build knows its family server (VITE_API_URL).
  const [familyOpen, setFamilyOpen] = useState(!!DEFAULT_API);

  // Signed in to the family account and its server logins arrived → straight in.
  useEffect(() => {
    if (cloud.session && accounts.length && !cloud.syncing)
      nav("/", { replace: true });
  }, [cloud.session, accounts.length, cloud.syncing]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (proxy && !server) setServer(proxy.server);
  }, [proxy]); // eslint-disable-line react-hooks/exhaustive-deps

  const [diag, setDiag] = useState<Diagnosis | null>(null);
  const [checking, setChecking] = useState(false);

  async function runCheck() {
    setChecking(true);
    setDiag(null);
    try {
      setDiag(await diagnose(server, username.trim(), password));
    } finally {
      setChecking(false);
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setDiag(null);
    const srv = normalizeServer(server);
    if (!srv || !username || !password) {
      setError("Fill in server, username and password.");
      return;
    }
    try {
      new URL(srv);
    } catch {
      setError("That server address doesn't look right — use the same one as in your IPTV app.");
      return;
    }
    const acc: Account = {
      // Same server + user → same id on every device, so synced bookmarks line up.
      id: accountId(srv, username.trim()),
      name: name.trim() || new URL(srv).hostname,
      server: srv,
      username: username.trim(),
      password,
      mode,
    };
    setBusy(true);
    const { base, via } = resolveBase(acc, proxy);
    try {
      const r = await new Xtream(acc, base).login();
      if (r.user_info.status && r.user_info.status !== "Active")
        throw new XtreamError(`Account status: ${r.user_info.status}`, "auth");
      await saveAccount(acc, true);
      nav("/", { replace: true });
    } catch (err) {
      setError(
        err instanceof XtreamError
          ? err.message
          : String((err as Error)?.message ?? err),
      );
      // Find out exactly why (direct connections only; the proxy reports its own errors).
      if (via === "direct") {
        setChecking(true);
        diagnose(server, username.trim(), password)
          .then(setDiag)
          .finally(() => setChecking(false));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="setup">
      <div className="setup-card">
        <div className="setup-brand">
          <span className="brand-mark">▶</span> Reelbox
        </div>
        {!cloud.session ? (
          <section className="setup-family">
            <button
              type="button"
              className="setup-family-head"
              onClick={() => setFamilyOpen((o) => !o)}
              aria-expanded={familyOpen}
            >
              <b>Family account</b>{" "}
              <span className="muted small">
                same login as MapPal · syncs bookmarks & servers
              </span>
            </button>
            {familyOpen && <FamilySignIn compact />}
          </section>
        ) : (
          <p className="hint">
            Signed in as {cloud.session.user.name || cloud.session.user.email}.{" "}
            {cloud.syncing
              ? "Syncing…"
              : accounts.length
                ? ""
                : "No servers saved yet — connect one below and it will appear on your other devices."}
          </p>
        )}
        <div className="setup-divider">
          <span>
            {cloud.session
              ? "Connect a server"
              : "or connect a server directly"}
          </span>
        </div>
        <form
          className="setup-form"
          onSubmit={submit}
          aria-label="Connect a server"
        >
          <label>
            Server URL
            <input
              data-autofocus
              inputMode="url"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              placeholder="http://my-server.example:8080"
              value={server}
              onChange={(e) => setServer(e.target.value)}
            />
          </label>
          <label>
            Username
            <input
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
            />
          </label>
          <label>
            Server password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="off"
            />
          </label>
          <label>
            Name (optional)
            <input
              placeholder="Home server"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <label>
            Connection
            <select
              value={mode}
              onChange={(e) => setMode(e.target.value as ConnectionMode)}
            >
              <option value="auto">
                Automatic{proxy ? " (proxy found)" : ""}
              </option>
              <option value="direct">Direct to server</option>
              <option value="proxy" disabled={!proxy}>
                Through Reelbox proxy{proxy ? "" : " (not available here)"}
              </option>
            </select>
          </label>
          {proxy && (
            <p className="hint">
              This page is served by the Reelbox proxy for {proxy.server}.
              Requests go through it, so CORS is not a problem.
            </p>
          )}
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          {checking && <p className="muted small">Checking the connection step by step…</p>}
          {diag && <ConnectionDebug d={diag} />}
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Connecting…" : "Connect"}
          </button>
          <button type="button" className="btn btn-ghost" onClick={runCheck} disabled={checking || !server}>
            {checking ? "Checking…" : "Check server (debug)"}
          </button>
          {accounts.length > 0 && (
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => nav(-1)}
            >
              <Icon name="back" /> Cancel
            </button>
          )}
        </form>
      </div>
    </div>
  );
}
