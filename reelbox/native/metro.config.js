// Metro config: the app shares Reelbox's core logic with the web app (../src/lib).
// Files there that differ per platform have a *.native.ts twin, which Metro prefers.
const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const config = getDefaultConfig(__dirname);
const shared = path.resolve(__dirname, '../src/lib');
config.watchFolders = [...(config.watchFolders ?? []), shared];

// Bare imports from shared files ('react', 'expo-file-system/legacy', …) must resolve from this
// app's node_modules — never from the web app's — or there would be two Reacts.
const anchor = path.join(__dirname, 'package.json');
const upstream = config.resolver.resolveRequest;
const fs = require('fs');
config.resolver.resolveRequest = (context, moduleName, platform) => {
  const fromShared = context.originModulePath.startsWith(shared + path.sep);
  // Web preview (`expo start --web`) runs the native app in a browser: use the *.native twins
  // there too, so it exercises the same code as the phone.
  if (platform === 'web') {
    const target = moduleName.startsWith('@shared/')
      ? path.join(shared, moduleName.slice('@shared/'.length))
      : fromShared && moduleName.startsWith('.')
        ? path.resolve(path.dirname(context.originModulePath), moduleName)
        : null;
    if (target) {
      for (const ext of ['.native.ts', '.native.tsx']) {
        if (fs.existsSync(target + ext)) return { type: 'sourceFile', filePath: target + ext };
      }
    }
  }
  const bare = !moduleName.startsWith('.') && !path.isAbsolute(moduleName);
  const ctx = fromShared && bare ? { ...context, originModulePath: anchor } : context;
  return upstream ? upstream(ctx, moduleName, platform) : ctx.resolveRequest(ctx, moduleName, platform);
};

module.exports = config;
