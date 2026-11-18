import { useMemo } from 'react';
import qrcode from 'qrcode-generator';
import { isIOS, isNative, isStandalone, isTizen, platformName } from '../lib/platform';
import { Icon } from '../components/ui';

/** Public home of the iPhone/browser web app, and of the Android / Samsung downloads. */
export const WEB_APP_URL = 'https://docunest.github.io/ANDROID-APPS/reelbox/';
const BUILDS_URL = 'https://github.com/docunest/ANDROID-APPS/releases?q=reelbox';
/** The native app for Expo Go (works with servers that block web apps). */
export const NATIVE_APP_URL = 'https://docunest.github.io/ANDROID-APPS/reelbox/app/';

function Qr({ text }: { text: string }) {
  const svg = useMemo(() => {
    const q = qrcode(0, 'M');
    q.addData(text);
    q.make();
    return q.createSvgTag({ cellSize: 6, margin: 3, scalable: true });
  }, [text]);
  // Generated markup from a QR library, from our own URL — not user content.
  return <div className="qr" role="img" aria-label={`QR code for ${text}`} dangerouslySetInnerHTML={{ __html: svg }} />;
}

export default function Install() {
  const here = /^https?:$/.test(location.protocol) && !isNative;
  // QR shows this page's address when it is a web install, otherwise the public web app.
  const url = here ? location.href.split('#')[0] : WEB_APP_URL;
  const secure = !here || window.isSecureContext;

  return (
    <div className="page install">
      <h1>Install on your devices</h1>
      <p className="muted">
        You are on: <b>{platformName}</b>
        {isStandalone && ' (installed)'}
      </p>

      <section className="panel install-qr">
        <Qr text={url} />
        <div>
          <h2>Open on another device</h2>
          <p>Scan with the iPhone or Android camera to open this app there.</p>
          <code className="url">{url}</code>
          {!secure && (
            <p className="hint">
              This address is not https, so iPhones won’t keep downloads or work offline from it. Use the https address (see README → “iPhone — web app”).
            </p>
          )}
        </div>
      </section>

      <section className="panel">
        <h2>iPhone app (Expo Go) · works with every server</h2>
        <p>
          The real app, not a web page: it can talk to Xtream servers that block web apps (“CORS”), plays in the background and in picture in
          picture, and keeps downloads on the phone.
        </p>
        <ol>
          <li>
            Install <b>Expo Go</b> from the{' '}
            <a href="https://apps.apple.com/app/expo-go/id982107779" target="_blank" rel="noreferrer">
              App Store
            </a>{' '}
            (free).
          </li>
          <li>
            Open{' '}
            <a href={NATIVE_APP_URL} target="_blank" rel="noreferrer">
              {NATIVE_APP_URL}
            </a>{' '}
            on the iPhone and tap <b>Open Reelbox in Expo Go</b>.
          </li>
        </ol>
      </section>

      <section className="panel">
        <h2>iPhone web app · no install at all</h2>
        <ol>
          <li>
            Open <b>{url}</b> in <b>Safari</b> (scan the QR code above with the camera).
          </li>
          <li>
            Tap <b>Share</b> <span className="share-glyph">⬆︎</span> → <b>Add to Home Screen</b> → <b>Add</b>.
          </li>
          <li>Open Reelbox from the home screen and sign in with the family account.</li>
        </ol>
        {isIOS && !isStandalone && !isNative && <p className="hint">You’re on an iPhone right now — do step 2 here.</p>}
      </section>

      <section className="panel">
        <h2>Android</h2>
        <ol>
          <li>
            Download <b>reelbox.apk</b> from the newest{' '}
            <a href={BUILDS_URL} target="_blank" rel="noreferrer">
              Reelbox release
            </a>
            .
          </li>
          <li>Open the file on the phone and allow “install unknown apps” for the browser/files app when asked.</li>
          <li>New versions install over the old one; downloads and settings stay.</li>
        </ol>
        <p className="muted small">Or use it like the iPhone: open the address in Chrome → ⋮ → Install app.</p>
      </section>

      <section className="panel">
        <h2>MacBook</h2>
        <ol>
          <li>
            Open the address in Chrome → ⋮ → <b>Cast, save and share → Install page as app</b>.
          </li>
          <li>
            Or in Safari: <b>File → Add to Dock</b>.
          </li>
        </ol>
      </section>

      <section className="panel">
        <h2>Samsung projector</h2>
        <ol>
          <li>
            Get <i>reelbox-samsung-unsigned.wgt</i> from the same{' '}
            <a href={BUILDS_URL} target="_blank" rel="noreferrer">
              release
            </a>
            .
          </li>
          <li>Sign and install it once from a computer with Tizen Studio (README → “Samsung projector”).</li>
          <li>Sign in without typing: open a one-time login link made with the admin API.</li>
        </ol>
        {isTizen && <p className="hint">You’re on the projector — it’s already installed.</p>}
      </section>

      <p className="muted small">
        <Icon name="external" size={14} /> Full instructions: README in <code>docunest/ANDROID-APPS/reelbox</code>.
      </p>
    </div>
  );
}
