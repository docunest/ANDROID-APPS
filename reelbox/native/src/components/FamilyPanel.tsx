// Family account (shared docunest backend): one login syncs favorites, bookmarks, resume points,
// picture settings and server logins between the iPhone app, the web app, the TV and the Mac.
import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { DEFAULT_API, lastApi, signIn, signInWithLink, signOut, syncNow, useCloud } from '@shared/cloud';
import { Button, Field, s, toast } from './ui';
import { C } from '@/theme';

export function FamilyPanel() {
  const cloud = useCloud();
  const [api, setApi] = useState(DEFAULT_API);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    lastApi().then((a) => a && setApi(a));
  }, []);

  if (cloud.session) {
    return (
      <View style={[s.card, { gap: 10 }]}>
        <Text style={s.h3}>Family account</Text>
        <Text style={s.text}>Signed in as {cloud.session.user.name || cloud.session.user.email}</Text>
        <Text style={s.muted}>
          {cloud.syncing ? 'Syncing…' : cloud.lastSync ? `Synced ${new Date(cloud.lastSync).toLocaleTimeString()}` : 'Not synced yet'}
          {cloud.pending ? ` · ${cloud.pending} change(s) waiting` : ''}
        </Text>
        {cloud.error ? <Text style={{ color: C.danger }}>{cloud.error}</Text> : null}
        <View style={[s.row, { flexWrap: 'wrap' }]}>
          <Button small title="Sync now" onPress={() => syncNow()} />
          <Button small kind="ghost" title="Sign out" onPress={() => signOut()} />
        </View>
      </View>
    );
  }

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setErr(null);
    try {
      await fn();
      toast('Signed in — your servers and bookmarks are syncing');
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const pasteLink = async () => {
    const text = (await Clipboard.getStringAsync()).trim();
    const q = text.includes('?') ? text.slice(text.indexOf('?') + 1) : '';
    const params = new URLSearchParams(q);
    const token = params.get('token');
    if (!token) return setErr('The clipboard has no Reelbox login link. Copy the whole link first.');
    run(() => signInWithLink(params.get('api') || api, token));
  };

  return (
    <View style={[s.card, { gap: 12 }]}>
      <Text style={s.h3}>Family account</Text>
      <Text style={s.muted}>Same login as the family’s other apps. Brings your servers, favorites, bookmarks and resume points from the other devices.</Text>
      {!DEFAULT_API ? <Field label="Family server address" value={api} onChangeText={setApi} placeholder="https://….vercel.app" keyboardType="url" /> : null}
      <Field label="Email" value={email} onChangeText={setEmail} keyboardType="email-address" textContentType="username" autoComplete="email" />
      <Field label="Password" value={password} onChangeText={setPassword} secureTextEntry textContentType="password" />
      {err ? <Text style={{ color: C.danger }}>{err}</Text> : null}
      <Button kind="primary" title="Sign in" busy={busy} disabled={!api || !email || !password} onPress={() => run(() => signIn(api, email.trim(), password))} />
      <Button kind="ghost" title="Paste a login link" onPress={pasteLink} />
    </View>
  );
}
