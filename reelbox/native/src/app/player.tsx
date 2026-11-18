// Full-screen player: expo-video (AVPlayer on iPhone, ExoPlayer on Android) with Reelbox's picture
// geometry on top — the video view is sized/positioned by frameRect(), so zoom, forced shape and
// cutting baked-in black bars work the same as in the web app (and the settings sync).
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Linking, PanResponder, Platform, Pressable, StyleSheet, Text, View, type LayoutChangeEvent } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useEvent, useEventListener } from 'expo';
import { useVideoPlayer, VideoAirPlayButton, VideoView, isPictureInPictureSupported, type VideoPlayer } from 'expo-video';
import * as ScreenOrientation from 'expo-screen-orientation';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import * as Clipboard from 'expo-clipboard';
import Slider from '@react-native-community/slider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useSession } from '@shared/session';
import { getSeriesInfo } from '@shared/catalog';
import { fmtTime, getProgress, recallRef, rememberRef, saveProgress, useMoments, usePicture } from '@shared/userdata';
import { episodeRef, refKey, type MediaRef, type Xtream } from '@shared/xtream';
import { frameRect, pictureSummary, ZOOM_MAX, ZOOM_MIN, type Picture } from '@shared/picture';
import { localFileFor } from '@/lib/downloads';
import { PicturePanel } from '@/components/PicturePanel';
import { Button, Loading, s, toast } from '@/components/ui';
import { C } from '@/theme';

const pipOk = (() => {
  try {
    return isPictureInPictureSupported();
  } catch {
    return false;
  }
})();

export default function PlayerScreen() {
  const { key, from } = useLocalSearchParams<{ key: string; from?: string }>();
  const { client, account } = useSession();
  const [ref, setRef] = useState<MediaRef | null>(null);
  const [src, setSrc] = useState<{ uri: string; offline: boolean } | null>(null);
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      const r = await recallRef(key);
      if (!alive) return;
      if (!r || !client) return setMissing(true);
      const local = await localFileFor(r);
      if (!alive) return;
      setRef(r);
      setSrc(local ? { uri: local, offline: true } : { uri: client.streamUrl(r, 'm3u8'), offline: false });
    })();
    return () => {
      alive = false;
    };
  }, [key, client]);

  useEffect(() => {
    ScreenOrientation.unlockAsync().catch(() => {});
    return () => {
      ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP).then(
        () => ScreenOrientation.unlockAsync(),
        () => {},
      );
    };
  }, []);

  if (missing)
    return (
      <View style={[s.center, { backgroundColor: '#000' }]}>
        <Text style={s.h2}>Can’t find this video</Text>
        <Button title="Close" onPress={() => router.back()} />
      </View>
    );
  if (!ref || !src || !client || !account) return <View style={{ flex: 1, backgroundColor: '#000' }}><Loading /></View>;
  return <Player key={refKey(ref)} ref_={ref} src={src} client={client} acc={account.id} from={from != null ? Number(from) : null} />;
}

function Player({ ref_: ref, src, client, acc, from }: { ref_: MediaRef; src: { uri: string; offline: boolean }; client: Xtream; acc: string; from: number | null }) {
  useEffect(() => {
    activateKeepAwakeAsync('player').catch(() => {});
    return () => {
      // may reject (or throw) if it never activated
      Promise.resolve()
        .then(() => deactivateKeepAwake('player'))
        .catch(() => {});
    };
  }, []);
  const insets = useSafeAreaInsets();
  const isLive = ref.kind === 'live';
  const player = useVideoPlayer(
    {
      uri: src.uri,
      contentType: isLive ? 'hls' : 'auto',
      metadata: { title: ref.seriesName ? `${ref.seriesName} · S${ref.season}E${ref.episode}` : ref.name, artist: ref.seriesName ? ref.name : undefined, artwork: ref.poster },
    },
    (p) => {
      p.timeUpdateEventInterval = 1;
      p.staysActiveInBackground = true;
      p.showNowPlayingNotification = true;
      p.allowsExternalPlayback = true;
      p.play();
    },
  );
  const { isPlaying } = useEvent(player, 'playingChange', { isPlaying: player.playing });
  const { status, error } = useEvent(player, 'statusChange', { status: player.status });
  const [time, setTime] = useState(0);
  const [dur, setDur] = useState(0);
  const [vsize, setVsize] = useState<{ w: number; h: number }>({ w: 0, h: 0 });
  useEventListener(player, 'timeUpdate', (e) => {
    setTime(e.currentTime);
    if (player.duration > 0 && isFinite(player.duration)) setDur(player.duration);
  });
  const readSize = useCallback((p: VideoPlayer) => {
    const sz = p.videoTrack?.size;
    if (sz && sz.width > 0 && sz.height > 0) setVsize({ w: sz.width, h: sz.height });
  }, []);
  useEventListener(player, 'videoTrackChange', () => readSize(player));
  useEventListener(player, 'sourceLoad', (e) => {
    if (e.duration > 0) setDur(e.duration);
    readSize(player);
  });

  // ---- resume ----
  const resumed = useRef(false);
  useEffect(() => {
    if (resumed.current || status !== 'readyToPlay' || isLive) return;
    resumed.current = true;
    readSize(player);
    (async () => {
      let at = from;
      if (at == null) {
        const p = await getProgress(acc, refKey(ref));
        at = p && !p.watched && p.pos > 20 ? p.pos - 3 : 0;
      }
      if (at && at > 0) {
        player.currentTime = at;
        toast(`Resuming at ${fmtTime(at)}`);
      }
    })();
  }, [status, from, acc, ref, player, isLive, readSize]);

  // ---- progress: every 5 s and when leaving ----
  const last = useRef({ t: 0, d: 0 });
  last.current = { t: time, d: dur };
  useEffect(() => {
    if (isLive) return;
    const iv = setInterval(() => {
      if (last.current.d > 0 && last.current.t > 1) saveProgress(acc, ref, last.current.t, last.current.d);
    }, 5000);
    return () => {
      clearInterval(iv);
      if (last.current.d > 0 && last.current.t > 1) saveProgress(acc, ref, last.current.t, last.current.d);
    };
  }, [acc, ref, isLive]);

  // ---- next episode ----
  const [next, setNext] = useState<MediaRef | null>(null);
  const [countdown, setCountdown] = useState<number | null>(null);
  useEffect(() => {
    if (ref.kind !== 'episode' || !ref.seriesId) return;
    getSeriesInfo(client, ref.seriesId)
      .then((info) => {
        const all = info.seasons.flatMap((se) => info.episodes[String(se.season_number)] ?? []);
        const i = all.findIndex((e) => String(e.id) === ref.id);
        const n = i >= 0 ? all[i + 1] : undefined;
        if (n) setNext(episodeRef(n, { id: ref.seriesId!, name: ref.seriesName ?? '', cover: ref.poster }));
      })
      .catch(() => {});
  }, [client, ref]);
  const goNext = useCallback(async () => {
    if (!next) return;
    await rememberRef(next);
    router.replace({ pathname: '/player', params: { key: refKey(next) } });
  }, [next]);
  useEventListener(player, 'playToEnd', () => {
    if (next) setCountdown(8);
  });
  useEffect(() => {
    if (countdown == null) return;
    if (countdown <= 0) {
      setCountdown(null);
      goNext();
      return;
    }
    const t = setTimeout(() => setCountdown((c) => (c == null ? null : c - 1)), 1000);
    return () => clearTimeout(t);
  }, [countdown, next, goNext]);

  // ---- picture geometry ----
  const pic = usePicture(acc, ref);
  const [live, setLive] = useState<Partial<Picture> | null>(null); // during a pinch/drag
  const picture: Picture = { ...pic.picture, ...(live ?? {}) };
  const [box, setBox] = useState({ w: 0, h: 0 });
  const onLayout = (e: LayoutChangeEvent) => setBox({ w: e.nativeEvent.layout.width, h: e.nativeEvent.layout.height });
  const known = vsize.w > 0 || picture.aspect != null;
  const rect = useMemo(() => (box.w ? frameRect(box, vsize, picture, picture.crop) : null), [box, vsize, picture]);

  // ---- controls ----
  const [controls, setControls] = useState(true);
  const [panel, setPanel] = useState(false);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const poke = useCallback(() => {
    setControls(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setControls(false), 4000);
  }, []);
  useEffect(() => {
    if (!isPlaying) {
      setControls(true);
      if (hideTimer.current) clearTimeout(hideTimer.current);
    } else poke();
  }, [isPlaying, poke]);

  // ---- gestures: tap = controls, double-tap = Fit/Fill, pinch = zoom, drag = move ----
  const g = useRef({ startZoom: 1, startDist: 0, startPan: { x: 0, y: 0 }, moved: false, lastTap: 0, pinching: false });
  const pictureRef = useRef(picture);
  pictureRef.current = picture;
  const rectRef = useRef(rect);
  rectRef.current = rect;
  const boxRef = useRef(box);
  boxRef.current = box;
  const commitRef = useRef(pic.set);
  commitRef.current = pic.set;
  const pan = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onMoveShouldSetPanResponder: () => true,
        onPanResponderGrant: (e) => {
          const p = pictureRef.current;
          g.current.startZoom = p.zoom;
          g.current.startPan = { x: p.panX, y: p.panY };
          g.current.moved = false;
          g.current.pinching = false;
          g.current.startDist = 0;
          const t = e.nativeEvent.touches;
          if (t.length >= 2) g.current.startDist = Math.hypot(t[0].pageX - t[1].pageX, t[0].pageY - t[1].pageY);
        },
        onPanResponderMove: (e, gs) => {
          const t = e.nativeEvent.touches;
          if (t.length >= 2) {
            const d = Math.hypot(t[0].pageX - t[1].pageX, t[0].pageY - t[1].pageY);
            if (!g.current.startDist) {
              g.current.startDist = d;
              g.current.startZoom = pictureRef.current.zoom;
            }
            g.current.pinching = true;
            g.current.moved = true;
            const z = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, g.current.startZoom * (d / g.current.startDist)));
            setLive((l) => ({ ...(l ?? {}), zoom: Math.round(z * 100) / 100 }));
            return;
          }
          if (g.current.pinching) return;
          if (Math.abs(gs.dx) + Math.abs(gs.dy) < 8) return;
          g.current.moved = true;
          const r = rectRef.current;
          const b = boxRef.current;
          const p = pictureRef.current;
          if (!r) return;
          const cw = r.w * ((p.crop?.r ?? 1) - (p.crop?.l ?? 0));
          const ch = r.h * ((p.crop?.b ?? 1) - (p.crop?.t ?? 0));
          const ox = Math.max(0, cw - b.w) / 2;
          const oy = Math.max(0, ch - b.h) / 2;
          if (!ox && !oy) return;
          const clamp = (v: number) => Math.min(1, Math.max(-1, v));
          setLive((l) => ({
            ...(l ?? {}),
            panX: ox ? clamp(g.current.startPan.x - gs.dx / ox) : p.panX,
            panY: oy ? clamp(g.current.startPan.y - gs.dy / oy) : p.panY,
          }));
        },
        onPanResponderRelease: () => {
          if (!g.current.moved) {
            const now = Date.now();
            if (now - g.current.lastTap < 300) {
              g.current.lastTap = 0;
              const f = pictureRef.current.fit;
              commitRef.current({ fit: f === 'contain' ? 'cover' : 'contain', zoom: 1, panX: 0, panY: 0 });
              toast(f === 'contain' ? 'Fill screen' : 'Fit');
            } else {
              g.current.lastTap = now;
              setTimeout(() => {
                if (g.current.lastTap === now) setControls((c) => !c);
              }, 300);
            }
            return;
          }
          setLive((l) => {
            if (l) commitRef.current(l);
            return null;
          });
        },
        onPanResponderTerminate: () => setLive(null),
      }),
    [],
  );

  const view = useRef<VideoView>(null);
  const moments = useMoments(acc);
  const close = () => router.back();
  const seek = (d: number) => {
    player.currentTime = Math.max(0, Math.min(dur || Infinity, player.currentTime + d));
    poke();
  };
  const url = client.externalUrl(ref);
  const summary = pictureSummary(picture);

  return (
    <View style={{ flex: 1, backgroundColor: '#000' }} onLayout={onLayout}>
      {rect ? (
        <View style={StyleSheet.absoluteFill} pointerEvents="none">
          <VideoView
            ref={view}
            player={player}
            nativeControls={false}
            contentFit={known ? 'fill' : 'contain'}
            allowsPictureInPicture
            startsPictureInPictureAutomatically
            style={{ position: 'absolute', left: rect.x, top: rect.y, width: rect.w, height: rect.h }}
          />
        </View>
      ) : null}
      <View style={StyleSheet.absoluteFill} {...pan.panHandlers} />

      {status === 'loading' && !isPlaying ? (
        <View style={StyleSheet.absoluteFill} pointerEvents="none">
          <Loading />
        </View>
      ) : null}

      {status === 'error' ? (
        <View style={[StyleSheet.absoluteFill, s.center, { backgroundColor: '#000c' }]}>
          <Text style={s.h2}>This video won’t play here</Text>
          <Text style={[s.muted, { textAlign: 'center' }]}>
            {error?.message ?? 'Unknown error'}
            {Platform.OS === 'ios' && /\.(mkv|avi|wmv|flv)$/i.test(url) ? '\n\niPhone’s built-in player can’t open .mkv/.avi files. Open it in VLC or Infuse (free apps) instead.' : ''}
          </Text>
          <View style={{ gap: 8, width: 260 }}>
            <Button title="Open in VLC" onPress={() => Linking.openURL(`vlc-x-callback://x-callback-url/stream?url=${encodeURIComponent(url)}`).catch(() => toast('VLC is not installed'))} />
            <Button title="Open in Infuse" onPress={() => Linking.openURL(`infuse://x-callback-url/play?url=${encodeURIComponent(url)}`).catch(() => toast('Infuse is not installed'))} />
            <Button title="Copy video link" onPress={() => Clipboard.setStringAsync(url).then(() => toast('Link copied'))} />
            <Button kind="ghost" title="Close" onPress={close} />
          </View>
        </View>
      ) : null}

      {countdown != null && next ? (
        <View style={[st.nextBox, { bottom: insets.bottom + 90 }]}>
          <Text style={s.text}>Next: {next.name} in {countdown}s</Text>
          <View style={s.row}>
            <Button small kind="primary" title="Play now" onPress={goNext} />
            <Button small title="Cancel" onPress={() => setCountdown(null)} />
          </View>
        </View>
      ) : null}

      {controls && status !== 'error' ? (
        <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
          <View style={[st.top, { paddingTop: insets.top + 8, paddingLeft: insets.left + 12, paddingRight: insets.right + 12 }]}>
            <Pressable onPress={close} hitSlop={12} accessibilityLabel="Close player">
              <Text style={st.icon}>✕</Text>
            </Pressable>
            <View style={{ flex: 1 }}>
              <Text style={s.h3} numberOfLines={1}>
                {ref.seriesName ? `${ref.seriesName} · S${ref.season}E${ref.episode}` : ref.name}
              </Text>
              <Text style={s.muted} numberOfLines={1}>
                {[ref.seriesName ? ref.name : null, src.offline ? 'Offline copy' : null, summary || null].filter(Boolean).join(' · ')}
              </Text>
            </View>
            {Platform.OS === 'ios' ? <VideoAirPlayButton tint={C.text} activeTint={C.accent} style={{ width: 34, height: 34 }} /> : null}
            {pipOk ? (
              <Pressable onPress={() => view.current?.startPictureInPicture().catch(() => toast('Picture in picture is not available'))} hitSlop={10} accessibilityLabel="Picture in picture">
                <Text style={st.icon}>⧉</Text>
              </Pressable>
            ) : null}
            <Pressable onPress={() => setPanel(true)} hitSlop={10} accessibilityLabel="Picture settings: zoom, shape, black bars">
              <Text style={[st.icon, { fontSize: 15, borderWidth: 1, borderColor: C.text, borderRadius: 6, paddingHorizontal: 6, paddingVertical: 2 }]}>Picture</Text>
            </Pressable>
          </View>

          <View style={st.middle} pointerEvents="box-none">
            {!isLive ? (
              <Pressable onPress={() => seek(-10)} hitSlop={12} accessibilityLabel="Back 10 seconds">
                <Text style={st.big}>↺10</Text>
              </Pressable>
            ) : null}
            <Pressable onPress={() => (isPlaying ? player.pause() : player.play())} hitSlop={16} accessibilityLabel={isPlaying ? 'Pause' : 'Play'}>
              <Text style={[st.big, { fontSize: 54 }]}>{isPlaying ? '❚❚' : '▶'}</Text>
            </Pressable>
            {!isLive ? (
              <Pressable onPress={() => seek(30)} hitSlop={12} accessibilityLabel="Forward 30 seconds">
                <Text style={st.big}>30↻</Text>
              </Pressable>
            ) : null}
          </View>

          <View style={[st.bottom, { paddingBottom: insets.bottom + 10, paddingLeft: insets.left + 12, paddingRight: insets.right + 12 }]}>
            {!isLive ? (
              <View style={[s.row, { gap: 8 }]}>
                <Text style={st.time}>{fmtTime(time)}</Text>
                <Slider
                  style={{ flex: 1, height: 36 }}
                  minimumValue={0}
                  maximumValue={dur || 1}
                  value={time}
                  minimumTrackTintColor={C.accent}
                  maximumTrackTintColor="#ffffff55"
                  thumbTintColor={C.accent}
                  onSlidingStart={() => hideTimer.current && clearTimeout(hideTimer.current)}
                  onSlidingComplete={(v) => {
                    player.currentTime = v;
                    setTime(v);
                    poke();
                  }}
                  accessibilityLabel="Seek"
                />
                <Text style={st.time}>-{fmtTime(Math.max(0, dur - time))}</Text>
              </View>
            ) : (
              <Text style={[s.muted, { color: C.danger }]}>● LIVE</Text>
            )}
            <View style={[s.row, { gap: 8, flexWrap: 'wrap' }]}>
              {!isLive ? (
                <Button
                  small
                  title="🔖 Bookmark"
                  onPress={() => {
                    moments.add(ref, Math.floor(player.currentTime));
                    toast(`Bookmarked at ${fmtTime(player.currentTime)}`);
                  }}
                />
              ) : null}
              <Button small title={`Zoom ${Math.round(picture.zoom * 100)}%`} onPress={() => setPanel(true)} />
              {next ? <Button small title="Next episode ▶" onPress={goNext} /> : null}
            </View>
          </View>
        </View>
      ) : null}

      <PicturePanel
        visible={panel}
        onClose={() => setPanel(false)}
        picture={pic.picture}
        custom={pic.custom}
        scopeLabel={pic.scopeLabel}
        set={pic.set}
        reset={pic.reset}
        makeDefault={() => pic.makeDefault().then(() => toast('Default for all videos saved'))}
      />
    </View>
  );
}

const st = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingBottom: 12, backgroundColor: '#000a' },
  middle: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 48 },
  bottom: { gap: 8, paddingTop: 8, backgroundColor: '#000a' },
  icon: { color: C.text, fontSize: 22 },
  big: { color: C.text, fontSize: 26, fontWeight: '700', textShadowColor: '#000', textShadowRadius: 6 },
  time: { color: C.text, fontVariant: ['tabular-nums'], fontSize: 13, minWidth: 54, textAlign: 'center' },
  nextBox: { position: 'absolute', right: 16, backgroundColor: C.bg2, borderRadius: 12, padding: 12, gap: 8, borderWidth: 1, borderColor: C.line },
});
