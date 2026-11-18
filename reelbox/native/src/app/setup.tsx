import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useSession } from '@shared/session';
import { formatExpiry } from '@shared/xtream';
import { Button, Field, s, toast } from '@/components/ui';
import { FamilyPanel } from '@/components/FamilyPanel';
import { connect, explain } from '@/lib/connect';
import { C } from '@/theme';

export default function Setup() {
  const { accounts, saveAccount } = useSession();
  const [server, setServer] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const hadAccounts = useState(accounts.length > 0)[0];

  // Signing in to the family account brings saved servers along — go straight to the library.
  useEffect(() => {
    if (!hadAccounts && accounts.length > 0) router.replace('/');
  }, [accounts.length, hadAccounts]);

  const submit = async () => {
    setBusy(true);
    setErr(null);
    try {
      const { account, login } = await connect({ server, username, password, name });
      await saveAccount(account);
      toast(`Connected · expires ${formatExpiry(login.user_info.exp_date)}`);
      router.replace('/');
    } catch (e) {
      setErr(explain(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={s.pad} keyboardShouldPersistTaps="handled">
        <Text style={s.h1}>Reelbox</Text>
        <FamilyPanel />
        <Text style={[s.muted, { textAlign: 'center' }]}>— or add a server directly —</Text>
        <View style={[s.card, { gap: 12 }]}>
          <Text style={s.h3}>Xtream server</Text>
          <Text style={s.muted}>Use the same details as your IPTV app.</Text>
          <Field label="Server URL" value={server} onChangeText={setServer} placeholder="https://my.server.com:8443" keyboardType="url" />
          <Field label="Username" value={username} onChangeText={setUsername} textContentType="none" />
          <Field label="Server password" value={password} onChangeText={setPassword} secureTextEntry textContentType="none" />
          <Field label="Name (optional)" value={name} onChangeText={setName} placeholder="Home" />
          {err ? <Text style={{ color: C.danger }}>{err}</Text> : null}
          <Button kind="primary" title="Connect" busy={busy} disabled={!server || !username || !password} onPress={submit} />
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
