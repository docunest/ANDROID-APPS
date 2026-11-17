import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { resolveBase, useSession } from '../lib/session';
import { normalizeServer, Xtream, XtreamError, type Account, type ConnectionMode } from '../lib/xtream';
import { Icon } from '../components/ui';

export default function Setup() {
  const { proxy, saveAccount, accounts } = useSession();
  const nav = useNavigate();
  const [server, setServer] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [mode, setMode] = useState<ConnectionMode>('auto');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (proxy && !server) setServer(proxy.server);
  }, [proxy]); // eslint-disable-line react-hooks/exhaustive-deps

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const srv = normalizeServer(server);
    if (!srv || !username || !password) {
      setError('Fill in server, username and password.');
      return;
    }
    const acc: Account = {
      id: Math.random().toString(36).slice(2, 10),
      name: name.trim() || new URL(srv).hostname,
      server: srv,
      username: username.trim(),
      password,
      mode,
    };
    setBusy(true);
    try {
      const { base } = resolveBase(acc, proxy);
      const r = await new Xtream(acc, base).login();
      if (r.user_info.status && r.user_info.status !== 'Active') throw new XtreamError(`Account status: ${r.user_info.status}`, 'auth');
      await saveAccount(acc, true);
      nav('/', { replace: true });
    } catch (err) {
      setError(err instanceof XtreamError ? err.message : String((err as Error)?.message ?? err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="setup">
      <form className="setup-card" onSubmit={submit}>
        <div className="setup-brand">
          <span className="brand-mark">▶</span> Reelbox
        </div>
        <p className="muted">Connect to your Xtream server.</p>
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
          <input autoCapitalize="off" autoCorrect="off" spellCheck={false} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </label>
        <label>
          Name (optional)
          <input placeholder="Home server" value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label>
          Connection
          <select value={mode} onChange={(e) => setMode(e.target.value as ConnectionMode)}>
            <option value="auto">Automatic{proxy ? ' (proxy found)' : ''}</option>
            <option value="direct">Direct to server</option>
            <option value="proxy" disabled={!proxy}>
              Through Reelbox proxy{proxy ? '' : ' (not available here)'}
            </option>
          </select>
        </label>
        {proxy && <p className="hint">This page is served by the Reelbox proxy for {proxy.server}. Requests go through it, so CORS is not a problem.</p>}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {busy ? 'Connecting…' : 'Connect'}
        </button>
        {accounts.length > 0 && (
          <button type="button" className="btn btn-ghost" onClick={() => nav(-1)}>
            <Icon name="back" /> Cancel
          </button>
        )}
      </form>
    </div>
  );
}
