import { useEffect, useState, type FormEvent } from 'react';
import { DEFAULT_API, lastApi, signIn } from '../lib/cloud';

/** Family account sign-in (same email + password as MapPal). */
export function FamilySignIn({ onDone, compact }: { onDone?: () => void; compact?: boolean }) {
  const [api, setApi] = useState(DEFAULT_API);
  const [showApi, setShowApi] = useState(!DEFAULT_API);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    lastApi().then((a) => {
      if (a) setApi(a);
      setShowApi(!a);
    });
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!api) {
      setShowApi(true);
      setError('Enter the family account server address.');
      return;
    }
    setBusy(true);
    try {
      await signIn(api, email.trim(), password);
      setPassword('');
      onDone?.();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className={`family-signin ${compact ? 'compact' : ''}`} onSubmit={submit} aria-label="Family account sign in">
      <label>
        Email
        <input type="email" autoComplete="username" autoCapitalize="off" value={email} onChange={(e) => setEmail(e.target.value)} />
      </label>
      <label>
        Password
        <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
      </label>
      {showApi ? (
        <label>
          Family account server
          <input inputMode="url" autoCapitalize="off" spellCheck={false} placeholder="https://your-api.vercel.app" value={api} onChange={(e) => setApi(e.target.value)} />
        </label>
      ) : (
        <button type="button" className="link-btn small" onClick={() => setShowApi(true)}>
          Server: {api.replace(/^https?:\/\//, '')} · change
        </button>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <button className="btn btn-primary" type="submit" disabled={busy || !email || !password}>
        {busy ? 'Signing in…' : 'Sign in'}
      </button>
    </form>
  );
}
