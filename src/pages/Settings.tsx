import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSession } from '../lib/session';
import { saveCatalogOffline } from '../lib/catalog';
import { clearCache } from '../lib/cache';
import { exportUserData, importUserData } from '../lib/userdata';
import { usePersisted } from '../lib/store';
import { isTizen, platformName } from '../lib/platform';
import { formatExpiry, type ConnectionMode, type LoginResponse } from '../lib/xtream';
import { Icon, toast } from '../components/ui';
import type { EnginePref } from './Player';

export default function Settings() {
  const { account, accounts, client, via, proxy, saveAccount, removeAccount, switchAccount, online } = useSession();
  const nav = useNavigate();
  const [info, setInfo] = useState<LoginResponse | null>(null);
  const [infoErr, setInfoErr] = useState<string | null>(null);
  const [saving, setSaving] = useState<string | null>(null);
  const [engine, setEngine] = usePersisted<EnginePref>('playerEngine', 'auto');
  const [tvMode, setTvMode] = usePersisted<'auto' | 'on' | 'off'>('tvMode', 'auto');
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!client || !online) return;
    client
      .login()
      .then(setInfo)
      .catch((e) => setInfoErr(e.message));
  }, [client, online]);

  if (!account || !client) return null;

  const download = (name: string, data: unknown) => {
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  return (
    <div className="page settings">
      <h1>Settings</h1>

      <section className="panel">
        <h2>Account</h2>
        <dl className="facts">
          <dt>Server</dt>
          <dd>{account.server}</dd>
          <dt>User</dt>
          <dd>{account.username}</dd>
          <dt>Connection</dt>
          <dd>{via === 'proxy' ? `Through Reelbox proxy (${proxy?.base})` : 'Direct to server'}</dd>
          {info && (
            <>
              <dt>Status</dt>
              <dd>{info.user_info.status}</dd>
              <dt>Expires</dt>
              <dd>{formatExpiry(info.user_info.exp_date)}</dd>
              <dt>Connections</dt>
              <dd>
                {info.user_info.active_cons} of {info.user_info.max_connections} in use
              </dd>
            </>
          )}
          {infoErr && (
            <>
              <dt>Status</dt>
              <dd className="error-text">{infoErr}</dd>
            </>
          )}
        </dl>
        <label>
          Connection mode
          <select value={account.mode} onChange={(e) => saveAccount({ ...account, mode: e.target.value as ConnectionMode })}>
            <option value="auto">Automatic</option>
            <option value="direct">Direct to server</option>
            <option value="proxy" disabled={!proxy}>
              Through proxy{proxy ? '' : ' (not available)'}
            </option>
          </select>
        </label>
        {accounts.length > 1 && (
          <label>
            Switch account
            <select value={account.id} onChange={(e) => switchAccount(e.target.value)}>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} ({a.username})
                </option>
              ))}
            </select>
          </label>
        )}
        <div className="actions">
          <button className="btn" onClick={() => nav('/setup')}>
            Add account
          </button>
          <button
            className="btn btn-danger"
            onClick={async () => {
              if (!confirm(`Sign out of ${account.name}? Downloads stay on the device.`)) return;
              await removeAccount(account.id);
              nav('/setup', { replace: true });
            }}
          >
            Sign out
          </button>
        </div>
      </section>

      <section className="panel">
        <h2>Offline</h2>
        <p className="muted">Lists you open are saved automatically. Save everything now to browse and search the full catalog without internet.</p>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={!!saving || !online}
            onClick={async () => {
              try {
                await saveCatalogOffline(client, setSaving, false);
                toast('Catalog saved for offline use');
              } catch (e) {
                toast(`Failed: ${(e as Error).message}`);
              } finally {
                setSaving(null);
              }
            }}
          >
            <Icon name="download" /> {saving ? `Saving ${saving}…` : 'Save catalog offline'}
          </button>
          <button
            className="btn"
            disabled={!!saving || !online}
            onClick={async () => {
              try {
                await saveCatalogOffline(client, setSaving, true);
                toast('Catalog and all episode lists saved');
              } catch (e) {
                toast(`Failed: ${(e as Error).message}`);
              } finally {
                setSaving(null);
              }
            }}
          >
            Also save all episode lists
          </button>
          <button
            className="btn"
            onClick={async () => {
              await clearCache(`${account.id}:`);
              toast('Saved catalog cleared — it reloads from the server');
            }}
          >
            Clear saved catalog
          </button>
        </div>
      </section>

      <section className="panel">
        <h2>Bookmarks backup</h2>
        <p className="muted">Favorites, bookmarks and watch history live on this device. Export them to move to another device.</p>
        <div className="actions">
          <button className="btn" onClick={async () => download(`reelbox-${account.name}-${new Date().toISOString().slice(0, 10)}.json`, await exportUserData(account.id))}>
            Export
          </button>
          <button className="btn" onClick={() => fileRef.current?.click()}>
            Import
          </button>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            hidden
            onChange={async (e) => {
              const f = e.target.files?.[0];
              if (!f) return;
              try {
                await importUserData(account.id, JSON.parse(await f.text()));
                toast('Imported');
              } catch (err) {
                toast((err as Error).message);
              }
              e.target.value = '';
            }}
          />
        </div>
      </section>

      <section className="panel">
        <h2>Player & display</h2>
        {isTizen && (
          <label>
            Video engine
            <select value={engine} onChange={(e) => {
              const v = e.target.value as EnginePref;
              setEngine(() => v);
            }}>
              <option value="auto">Automatic (Samsung player for MKV/AVI)</option>
              <option value="html">Web player</option>
              <option value="avplay">Samsung player (AVPlay)</option>
            </select>
          </label>
        )}
        <label>
          TV layout (big text, remote friendly)
          <select value={tvMode} onChange={(e) => {
              const v = e.target.value as 'auto' | 'on' | 'off';
              setTvMode(() => v);
            }}>
            <option value="auto">Automatic</option>
            <option value="on">On</option>
            <option value="off">Off</option>
          </select>
        </label>
        <p className="muted small">
          Running as: {platformName} · Reelbox {__APP_VERSION__}
        </p>
      </section>
    </div>
  );
}
