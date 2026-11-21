import { useEffect } from 'react';
import { Stack, ThemeProvider, DarkTheme } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SessionProvider } from '@shared/session';
import { startCloud } from '@shared/cloud';
import { resumeAllOnLaunch } from '@/lib/downloads';
import { ToastHost } from '@/components/ui';
import { C } from '@/theme';

const theme = {
  ...DarkTheme,
  colors: { ...DarkTheme.colors, background: C.bg, card: C.bg2, text: C.text, border: C.line, primary: C.accent },
};

export default function Root() {
  useEffect(() => {
    startCloud();
    resumeAllOnLaunch();
  }, []);
  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: C.bg }}>
      <ThemeProvider value={theme}>
        <SessionProvider>
          <StatusBar style="light" />
          <Stack screenOptions={{ headerStyle: { backgroundColor: C.bg2 }, headerTintColor: C.text, contentStyle: { backgroundColor: C.bg } }}>
            <Stack.Screen name="(tabs)" options={{ headerShown: false, title: 'Reelbox' }} />
            <Stack.Screen name="movie/[id]" options={{ title: '' }} />
            <Stack.Screen name="series/[id]" options={{ title: '' }} />
            <Stack.Screen name="player" options={{ headerShown: false, presentation: 'fullScreenModal', animation: 'fade', gestureEnabled: false }} />
            <Stack.Screen name="setup" options={{ title: 'Add your server' }} />
            <Stack.Screen name="settings" options={{ title: 'Settings' }} />
            <Stack.Screen name="search" options={{ title: 'Search' }} />
          </Stack>
          <ToastHost />
        </SessionProvider>
      </ThemeProvider>
    </GestureHandlerRootView>
  );
}
