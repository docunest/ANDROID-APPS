import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { lastApi, signInWithLink } from '../lib/cloud';
import { Spinner } from '../components/ui';

/** #/login?token=…[&api=…] — single-use link minted by the admin API (no password typing on a TV). */
export default function MagicLogin() {
  const [params] = useSearchParams();
  const nav = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const once = useRef(false);

  useEffect(() => {
    if (once.current) return;
    once.current = true;
    (async () => {
      const token = params.get('token') ?? '';
      const api = params.get('api') || (await lastApi());
      try {
        if (!api) throw new Error('This link does not say which server to use. Sign in with email and password instead.');
        await signInWithLink(api, token);
        nav('/', { replace: true });
      } catch (e) {
        setError((e as Error).message);
      }
    })();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="setup">
      <div className="setup-card">
        <div className="setup-brand">
          <span className="brand-mark">▶</span> Reelbox
        </div>
        {error ? (
          <>
            <p className="form-error" role="alert">
              {error}
            </p>
            <button className="btn" onClick={() => nav('/setup', { replace: true })}>
              Sign in another way
            </button>
          </>
        ) : (
          <Spinner label="Signing you in…" />
        )}
      </div>
    </div>
  );
}
