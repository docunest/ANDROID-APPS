// Active account, connection resolution (direct vs proxy) and online status.
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePersisted } from './store';
import { normalizeServer, Xtream, type Account } from './xtream';

export interface ProxyInfo {
  base: string; // absolute URL of the proxy endpoint, e.g. https://host/xt
  server: string; // the Xtream server the proxy forwards to
}

let proxyPromise: Promise<ProxyInfo | null> | null = null;

/** The bundled server (server/proxy.mjs) answers /xt/__reelbox. Absent on Tizen/native/static hosting. */
export function detectProxy(): Promise<ProxyInfo | null> {
  if (proxyPromise) return proxyPromise;
  proxyPromise = (async () => {
    if (!/^https?:$/.test(location.protocol)) return null;
    const base = new URL('xt', location.href.split('#')[0]).href;
    try {
      const r = await fetch(`${base}/__reelbox`, { cache: 'no-store' });
      if (!r.ok) return null;
      const j = await r.json();
      return j && typeof j.server === 'string' ? { base, server: normalizeServer(j.server) } : null;
    } catch {
      return null;
    }
  })();
  return proxyPromise;
}

export function resolveBase(acc: Account, proxy: ProxyInfo | null): { base: string; via: 'direct' | 'proxy' } {
  if (acc.mode === 'proxy' && proxy) return { base: proxy.base, via: 'proxy' };
  if (acc.mode === 'auto' && proxy && (!acc.server || normalizeServer(acc.server) === proxy.server)) {
    return { base: proxy.base, via: 'proxy' };
  }
  return { base: acc.server, via: 'direct' };
}

export function useOnline(): boolean {
  const [online, setOnline] = useState(navigator.onLine);
  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);
  return online;
}

interface Session {
  ready: boolean;
  accounts: Account[];
  account: Account | null;
  client: Xtream | null;
  via: 'direct' | 'proxy' | null;
  proxy: ProxyInfo | null;
  online: boolean;
  saveAccount: (a: Account, makeActive?: boolean) => Promise<void>;
  removeAccount: (id: string) => Promise<void>;
  switchAccount: (id: string) => Promise<void>;
}

const Ctx = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [accounts, mutateAccounts, accReady] = usePersisted<Account[]>('accounts', []);
  const [activeId, mutateActive, activeReady] = usePersisted<string | null>('activeAccount', null);
  const [proxy, setProxy] = useState<ProxyInfo | null>(null);
  const [proxyReady, setProxyReady] = useState(false);
  const online = useOnline();

  useEffect(() => {
    detectProxy().then((p) => {
      setProxy(p);
      setProxyReady(true);
    });
  }, []);

  const account = accounts.find((a) => a.id === activeId) ?? accounts[0] ?? null;
  const { client, via } = useMemo(() => {
    if (!account) return { client: null, via: null };
    const r = resolveBase(account, proxy);
    return { client: new Xtream(account, r.base), via: r.via };
  }, [account, proxy]);

  const value: Session = {
    ready: accReady && activeReady && proxyReady,
    accounts,
    account,
    client,
    via,
    proxy,
    online,
    saveAccount: async (a, makeActive = true) => {
      await mutateAccounts((list) => {
        const i = list.findIndex((x) => x.id === a.id);
        return i >= 0 ? list.map((x) => (x.id === a.id ? a : x)) : [...list, a];
      });
      if (makeActive) await mutateActive(() => a.id);
    },
    removeAccount: async (id) => {
      await mutateAccounts((list) => list.filter((x) => x.id !== id));
      if (activeId === id) await mutateActive(() => null);
    },
    switchAccount: async (id) => {
      await mutateActive(() => id);
    },
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): Session {
  const s = useContext(Ctx);
  if (!s) throw new Error('useSession outside provider');
  return s;
}

/** For pages that are only rendered once an account exists. */
export function useClient(): Xtream {
  const { client } = useSession();
  if (!client) throw new Error('no account');
  return client;
}
