// Idempotent tweaks to the generated native projects (run after `cap sync`):
//  - Android: allow plain-http Xtream servers (cleartext traffic)
//  - iOS: App Transport Security exception so http:// servers and streams load
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const manifest = path.join(root, 'android/app/src/main/AndroidManifest.xml');
if (fs.existsSync(manifest)) {
  let m = fs.readFileSync(manifest, 'utf8');
  if (!m.includes('usesCleartextTraffic')) {
    m = m.replace('<application', '<application\n        android:usesCleartextTraffic="true"');
    fs.writeFileSync(manifest, m);
    console.log('android: enabled cleartext traffic');
  }
}

const plist = path.join(root, 'ios/App/App/Info.plist');
if (fs.existsSync(plist)) {
  let p = fs.readFileSync(plist, 'utf8');
  if (!p.includes('NSAppTransportSecurity')) {
    const ats = `\t<key>NSAppTransportSecurity</key>
\t<dict>
\t\t<key>NSAllowsArbitraryLoads</key>
\t\t<true/>
\t\t<key>NSAllowsArbitraryLoadsForMedia</key>
\t\t<true/>
\t</dict>
\t<key>LSApplicationQueriesSchemes</key>
\t<array>
\t\t<string>vlc-x-callback</string>
\t\t<string>vlc</string>
\t</array>
`;
    p = p.replace(/<\/dict>\s*<\/plist>\s*$/, `${ats}</dict>\n</plist>\n`);
    fs.writeFileSync(plist, p);
    console.log('ios: added App Transport Security exception');
  }
}
