import { Redirect } from 'expo-router';
import { Tabs } from 'expo-router/tabs';
import { Text, type ColorValue } from 'react-native';
import { useSession } from '@shared/session';
import { Loading } from '@/components/ui';
import { C } from '@/theme';

const icon = (glyph: string) => ({ color }: { color: ColorValue }) => <Text style={{ color, fontSize: 20 }}>{glyph}</Text>;

export default function TabsLayout() {
  const { ready, account } = useSession();
  if (!ready) return <Loading />;
  if (!account) return <Redirect href="/setup" />;
  return (
    <Tabs
      screenOptions={{
        headerStyle: { backgroundColor: C.bg2 },
        headerTintColor: C.text,
        tabBarStyle: { backgroundColor: C.bg2, borderTopColor: C.line },
        tabBarActiveTintColor: C.accent,
        tabBarInactiveTintColor: C.muted,
        sceneStyle: { backgroundColor: C.bg },
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Home', tabBarIcon: icon('⌂') }} />
      <Tabs.Screen name="movies" options={{ title: 'Movies', tabBarIcon: icon('🎬') }} />
      <Tabs.Screen name="series" options={{ title: 'Series', tabBarIcon: icon('📺') }} />
      <Tabs.Screen name="live" options={{ title: 'Live', tabBarIcon: icon('📡') }} />
      <Tabs.Screen name="library" options={{ title: 'Library', tabBarIcon: icon('★') }} />
    </Tabs>
  );
}
