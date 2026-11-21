// Sign in to an Xtream server from the native app. No CORS here, so a failure is a real one.
import { accountId, normalizeServer, Xtream, XtreamError, type Account } from '@shared/xtream';

/** Addresses typed without http/https: try https first (most providers), then http. */
export async function connect(input: { server: string; username: string; password: string; name?: string }) {
  const typed = input.server.trim();
  const candidates = /^https?:\/\//i.test(typed) ? [typed] : [`https://${typed}`, `http://${typed}`];
  let lastErr: unknown = null;
  for (const c of candidates) {
    const server = normalizeServer(c);
    const account: Account = {
      id: accountId(server, input.username.trim()),
      name: input.name?.trim() || new URL(server).hostname,
      server,
      username: input.username.trim(),
      password: input.password,
      mode: 'direct',
    };
    try {
      const login = await new Xtream(account, server).login();
      return { account, login };
    } catch (e) {
      lastErr = e;
      if (e instanceof XtreamError && e.kind === 'auth') break; // server answered: no point trying http
    }
  }
  throw lastErr;
}

export function explain(e: unknown): string {
  if (e instanceof XtreamError) {
    if (e.kind === 'cors' || e.kind === 'network')
      return `${e.kind === 'network' ? 'The server took too long to answer.' : 'Could not reach the server.'} Check the address and port (copy them from your IPTV app), and that this phone has internet.`;
    return e.message;
  }
  return (e as Error)?.message || String(e);
}
