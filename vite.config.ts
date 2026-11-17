import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import pkg from './package.json' with { type: 'json' };

// Modes:
//   (default)   web / PWA build → dist/        (served by server/proxy.mjs or any static host)
//   capacitor   iOS + Android  → dist/        (no service worker; files ship inside the app)
//   tizen       Samsung Tizen  → dist-tizen/  (classic script, old-Chromium syntax, no SW)
export default defineConfig(({ mode }) => {
  const tizen = mode === 'tizen';
  const pwa = mode !== 'tizen' && mode !== 'capacitor';
  return {
    base: './',
    define: {
      __APP_VERSION__: JSON.stringify(pkg.version),
      __PWA__: JSON.stringify(pwa),
    },
    server: {
      // `npm run dev` talks to the Xtream server through the same /xt proxy path as production.
      proxy: process.env.XTREAM_URL
        ? {
            '/xt': {
              target: process.env.XTREAM_URL,
              changeOrigin: true,
              rewrite: (p) => p.replace(/^\/xt/, ''),
              bypass: (req, res) => {
                if (req.url === '/xt/__reelbox') {
                  res!.setHeader('content-type', 'application/json');
                  res!.end(JSON.stringify({ server: process.env.XTREAM_URL }));
                  return false;
                }
              },
            },
          }
        : undefined,
    },
    build: {
      outDir: tizen ? 'dist-tizen' : 'dist',
      target: tizen ? ['chrome69'] : ['es2020', 'safari15', 'chrome90'],
      assetsInlineLimit: 0,
      chunkSizeWarningLimit: 900,
      rollupOptions: tizen ? { output: { format: 'iife', inlineDynamicImports: true, entryFileNames: 'app.js', assetFileNames: '[name][extname]' } } : undefined,
    },
    plugins: [
      react(),
      tizen && {
        // Tizen loads from file:// — module scripts are blocked there, so emit a classic script.
        name: 'tizen-classic-script',
        transformIndexHtml: {
          order: 'post' as const,
          handler: (html: string) =>
            html
              .replace(/<script type="module" crossorigin src="([^"]+)"><\/script>/, '')
              .replace(/ crossorigin/g, '')
              .replace('</body>', '<script src="$WEBAPIS/webapis/webapis.js"></script>\n<script src="./app.js"></script>\n</body>'),
        },
      },
      pwa &&
        VitePWA({
          injectRegister: null,
          registerType: 'autoUpdate',
          filename: 'sw.js',
          includeAssets: ['icon.svg', 'apple-touch-icon.png'],
          manifest: {
            name: 'Reelbox',
            short_name: 'Reelbox',
            description: 'Your Xtream video library — browse, bookmark, play and download for offline.',
            theme_color: '#0d0f14',
            background_color: '#0d0f14',
            display: 'standalone',
            orientation: 'any',
            start_url: './',
            scope: './',
            icons: [
              { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
              { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
              { src: 'icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
            ],
          },
          workbox: {
            globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
            navigateFallback: 'index.html',
            navigateFallbackDenylist: [/^\/xt\//],
            cleanupOutdatedCaches: true,
            runtimeCaching: [
              {
                // Posters/backdrops: cache CORS-readable copies so the library looks right offline.
                // Opaque responses are never stored (browsers bill them ~7 MB each against quota).
                urlPattern: ({ request }) => request.destination === 'image',
                handler: async ({ request }) => {
                  const cache = await caches.open('reelbox-images');
                  const hit = await cache.match(request.url);
                  if (hit) return hit;
                  try {
                    const res = await fetch(request.url, { mode: 'cors', credentials: 'omit' });
                    if (res.ok) {
                      cache.put(request.url, res.clone());
                      return res;
                    }
                  } catch {
                    /* host without CORS — fall through */
                  }
                  return fetch(request);
                },
              },
            ],
          },
        }),
    ].filter(Boolean),
  };
});
