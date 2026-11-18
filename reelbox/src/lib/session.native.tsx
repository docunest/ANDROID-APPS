// Native (Expo) session: same API as session.tsx, minus the web proxy (native apps call the
// Xtream server directly — no CORS, no mixed-content rules).
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import * as Network from 'expo-network';
import { usePersisted } from './store';
import { Xtream, type Account } from './xtream';

export interface ProxyInfo {
  base: string;
  server: string;
}

export function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => {
    Network.getNetworkStateAsync()
      .then((s) => setOnline(s.isConnected !== false))
      .catch(() => {});
    const sub = Network.addNetworkStateListener((s) => setOnline(s.isConnected !== false));
    return () => sub.remove();
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
  const online = useOnline();
  const account = accounts.find((a) => a.id === activeId) ?? accounts[0] ?? null;
  const client = useMemo(() => (account ? new Xtream(account, account.server) : null), [account]);

  const value: Session = {
    ready: accReady && activeReady,
    accounts,
    account,
    client,
    via: client ? 'direct' : null,
    proxy: null,
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

export function useClient(): Xtream {
  const { client } = useSession();
  if (!client) throw new Error('no account');
  return client;
}
