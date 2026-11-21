import { useState } from 'react';
import { Alert, ScrollView, Text, View } from 'react-native';
import { router } from 'expo-router';
import Constants from 'expo-constants';
import { useSession } from '@shared/session';
import { saveCatalogOffline } from '@shared/catalog';
import { clearCache } from '@shared/cache';
import { Button, Chip, s, toast } from '@/components/ui';
import { FamilyPanel } from '@/components/FamilyPanel';

export default function Settings() {
  const { accounts, account, client, switchAccount, removeAccount } = useSession();
  const [step, setStep] = useState<string | null>(null);
  return (
    <ScrollView contentContainerStyle={s.pad}>
      <FamilyPanel />
      <View style={[s.card, { gap: 10 }]}>
        <Text style={s.h3}>Servers</Text>
        <View style={[s.row, { flexWrap: 'wrap', gap: 8 }]}>
          {accounts.map((a) => (
            <Chip key={a.id} label={`${a.name} · ${a.username}`} active={a.id === account?.id} onPress={() => switchAccount(a.id)} />
          ))}
        </View>
        <View style={[s.row, { flexWrap: 'wrap' }]}>
          <Button small title="Add a server" onPress={() => router.push('/setup')} />
          {account ? (
            <Button
              small
              kind="danger"
              title="Remove this server"
              onPress={() =>
                Alert.alert('Remove this server?', `${account.name} (${account.username})`, [
                  { text: 'Cancel', style: 'cancel' },
                  { text: 'Remove', style: 'destructive', onPress: () => removeAccount(account.id) },
                ])
              }
            />
          ) : null}
        </View>
      </View>
      <View style={[s.card, { gap: 10 }]}>
        <Text style={s.h3}>Offline</Text>
        <Text style={s.muted}>Save the whole movie, series and channel list so you can browse and search without internet. Videos themselves: tap Download on a movie or episode.</Text>
        <Button
          small
          busy={!!step}
          title={step ? step : 'Save catalog for offline'}
          onPress={async () => {
            if (!client) return;
            try {
              await saveCatalogOffline(client, (l) => setStep(`Saving ${l}…`));
              toast('Catalog saved for offline');
            } catch (e) {
              toast((e as Error).message);
            } finally {
              setStep(null);
            }
          }}
        />
        <Button small kind="ghost" title="Refresh catalog from server" onPress={() => clearCache(`${account?.id}:`).then(() => toast('Catalog will reload'))} />
      </View>
      <Text style={[s.muted, { textAlign: 'center' }]}>Reelbox {Constants.expoConfig?.version ?? ''} · native app</Text>
    </ScrollView>
  );
}
