import type { CapacitorConfig } from '@capacitor/cli';

// iOS + Android native shells. Build web assets first: `npm run cap:sync`.
const config: CapacitorConfig = {
  appId: 'app.reelbox.player',
  appName: 'Reelbox',
  webDir: 'dist',
  backgroundColor: '#0d0f14',
  server: {
    androidScheme: 'https',
    // Xtream servers are usually plain http.
    cleartext: true,
  },
  android: {
    allowMixedContent: true,
  },
  plugins: {
    // Route fetch/XHR through native HTTP: no CORS limits when talking to the Xtream server.
    CapacitorHttp: { enabled: true },
  },
};

export default config;
