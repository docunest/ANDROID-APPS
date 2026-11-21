// Connection diagnostics: when "Connect" fails, find out *why* instead of guessing.
// Browsers hide the reason for a failed request (it is always just "TypeError: Load failed"),
// so we probe the same address in different ways and compare:
//   - normal request fails, but an opaque (no-cors) request succeeds → server is up, CORS missing
//   - both fail → server not reachable from this device (DNS, port, certificate, firewall)
//   - http address from an https page → blocked as mixed content before it leaves the phone
import { normalizeServer } from './xtream';

export type Verdict = 'ok' | 'mixed' | 'cors' | 'unreachable' | 'auth' | 'not-xtream' | 'http-error' | 'timeout' | 'bad-url';

export interface Check {
  label: string;
  ok: boolean | null; // null = informational
  detail: string;
}

export interface Diagnosis {
  verdict: Verdict;
  summary: string;
  fixes: string[];
  checks: Check[];
  report: string; // copyable text, password masked
}

const mask = (url: string, password: string) => (password ? url.split(encodeURIComponent(password)).join('•••').split(password).join('•••') : url);

async function timed<T>(fn: (signal: AbortSignal) => Promise<T>, ms: number): Promise<{ v?: T; err?: unknown; ms: number; timeout?: boolean }> {
  const ctrl = new AbortController();
  const t0 = performance.now();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    const v = await fn(ctrl.signal);
    return { v, ms: Math.round(performance.now() - t0) };
  } catch (err) {
    return { err, ms: Math.round(performance.now() - t0), timeout: ctrl.signal.aborted };
  } finally {
    clearTimeout(timer);
  }
}

const errText = (e: unknown) => (e instanceof Error ? `${e.name}: ${e.message}` : String(e));

export async function diagnose(input: string, username: string, password: string): Promise<Diagnosis> {
  const checks: Check[] = [];
  const page = location.origin;
  const pageHttps = location.protocol === 'https:';
  checks.push({ label: 'This app runs at', ok: null, detail: `${page} (${pageHttps ? 'https' : location.protocol.replace(':', '')})` });
  checks.push({ label: 'Browser', ok: null, detail: navigator.userAgent });

  const server = normalizeServer(input);
  let url: URL;
  try {
    url = new URL(server);
  } catch {
    return finish('bad-url', 'The server address is not a valid web address.', ['Enter it like https://my.server.com:8443 (the same address your IPTV app uses).'], checks, input, password);
  }
  checks.push({ label: 'Server address used', ok: null, detail: `${server}${input.trim() !== server ? `  (you typed “${input.trim()}”)` : ''}` });

  const q = new URLSearchParams({ username, password });
  const api = `${server}/player_api.php?${q}`;
  checks.push({ label: 'Login request', ok: null, detail: mask(api, password) });

  // 1. Mixed content: decided by the browser before anything is sent.
  if (pageHttps && url.protocol === 'http:' && !['localhost', '127.0.0.1'].includes(url.hostname)) {
    checks.push({ label: 'http address from an https app', ok: false, detail: 'Safari/Chrome block this before it leaves the device (“mixed content”).' });
    const httpsTry = new URL(server);
    httpsTry.protocol = 'https:';
    if (httpsTry.port === '80') httpsTry.port = '';
    const probe = await timed((signal) => fetch(`${httpsTry.origin}/player_api.php`, { mode: 'no-cors', cache: 'no-store', signal }), 8000);
    checks.push({
      label: `Same server over https (${httpsTry.origin})`,
      ok: !probe.err,
      detail: probe.err ? `not reachable (${probe.timeout ? 'timeout' : errText(probe.err)})` : `answers (${probe.ms} ms)`,
    });
    return finish(
      'mixed',
      'The address starts with http://, and an https web app is not allowed to call an http server.',
      [
        probe.err ? 'Use your server’s https address (ask your provider for the https port), e.g. https://host:8443.' : `Use ${httpsTry.origin} as the server address — it answers over https.`,
        'Or use the Reelbox proxy on an https address (README → “Proxy, only if needed”).',
      ],
      checks,
      input,
      password,
    );
  }

  // 2. The real request, as the app makes it.
  const real = await timed((signal) => fetch(api, { cache: 'no-store', signal }), 15000);
  if (!real.err && real.v) {
    const res = real.v;
    const body = await res.text().catch(() => '');
    checks.push({
      label: 'Login request (normal)',
      ok: res.ok,
      detail: `HTTP ${res.status} ${res.statusText} · ${res.headers.get('content-type') ?? 'no content-type'} · ${real.ms} ms`,
    });
    checks.push({ label: 'Answer starts with', ok: null, detail: mask(body.slice(0, 160).replace(/\s+/g, ' '), password) || '(empty)' });
    if (!res.ok) {
      return finish(
        res.status === 401 || res.status === 403 ? 'auth' : 'http-error',
        `The server answered HTTP ${res.status}.`,
        res.status === 404
          ? ['The address is reachable but has no player_api.php there — check the port / path your IPTV app uses.']
          : ['Compare the address with the one in your IPTV app (port, http/https).'],
        checks,
        input,
        password,
      );
    }
    let json: any;
    try {
      json = JSON.parse(body);
    } catch {
      return finish('not-xtream', 'The server answered, but not with Xtream data.', ['Check the port and path — it should be the same “server URL” your IPTV app uses.', 'If the answer above is a web page (Cloudflare, login page), that server blocks apps like this one.'], checks, input, password);
    }
    const auth = Number(json?.user_info?.auth);
    checks.push({ label: 'Xtream login', ok: auth === 1, detail: auth === 1 ? `ok · status ${json.user_info.status} · expires ${json.user_info.exp_date ?? 'never'}` : 'auth = 0 (server rejected the username/password)' });
    if (auth !== 1) {
      return finish('auth', 'The server rejected the username or password.', ['Copy them exactly from the IPTV app (they are case-sensitive; watch for spaces at the end).', 'Some providers use a different server address per account — check it too.'], checks, input, password);
    }
    return finish('ok', 'Everything works — the server is reachable and the login is accepted.', [], checks, input, password);
  }
  checks.push({ label: 'Login request (normal)', ok: false, detail: real.timeout ? 'no answer within 15 s' : `${errText(real.err)} (browsers don’t say why)` });

  // 3. Same request without needing CORS permission: tells "up but blocks browsers" from "unreachable".
  const opaque = await timed((signal) => fetch(api, { mode: 'no-cors', cache: 'no-store', signal }), 15000);
  checks.push({
    label: 'Same request without CORS (no-cors probe)',
    ok: !opaque.err,
    detail: opaque.err ? (opaque.timeout ? 'no answer within 15 s' : errText(opaque.err)) : `server answered (${opaque.ms} ms) — but the browser may not read it`,
  });

  if (!opaque.err) {
    return finish(
      'cors',
      'Your server is up, but it doesn’t allow web apps to read its answers (no CORS header). IPTV apps are native, so they aren’t affected.',
      [
        'If you run the server (Xtream UI / XUI panel, nginx): add the header  Access-Control-Allow-Origin: *  to player_api.php and the stream paths.',
        'Or run the Reelbox proxy on an https address and open the app from there (README → “Proxy, only if needed”).',
        'Easiest: use the Reelbox app in Expo Go (iPhone and Android) — apps aren’t affected by this. Install: https://docunest.github.io/ANDROID-APPS/reelbox/app/',
      ],
      checks,
      input,
      password,
    );
  }
  if (real.timeout || opaque.timeout) {
    return finish('timeout', 'The server did not answer in time.', ['Check that this device has internet and the server is up (does the IPTV app work on this same device and network right now?).'], checks, input, password);
  }
  return finish(
    'unreachable',
    'This device cannot reach the server at that address at all.',
    [
      'Check the exact address and port from the IPTV app (http vs https, :8080 / :8443 / :443).',
      url.protocol === 'https:' ? 'If the server’s https certificate is self-signed or for another name, browsers refuse it while IPTV apps often accept it — try the http address through the proxy instead.' : '',
      'If it only works on your home network or VPN, connect to that first.',
    ].filter(Boolean),
    checks,
    input,
    password,
  );
}

function finish(verdict: Verdict, summary: string, fixes: string[], checks: Check[], input: string, password: string): Diagnosis {
  const lines = [
    `Reelbox connection check — ${new Date().toISOString()}`,
    `Result: ${verdict} — ${summary}`,
    ...checks.map((c) => `${c.ok === null ? '•' : c.ok ? '✓' : '✗'} ${c.label}: ${c.detail}`),
    ...fixes.map((f) => `→ ${f}`),
  ];
  void input;
  return { verdict, summary, fixes, checks, report: mask(lines.join('\n'), password) };
}
