// Playback engines with one interface:
//  - HtmlEngine: <video> + hls.js where the browser lacks native HLS (Android, desktop Chrome/Firefox)
//  - AvPlayEngine: Samsung Tizen webapis.avplay — plays MKV/AVI/HEVC/multi-audio that <video> can't

export interface Track {
  id: string;
  label: string;
  lang?: string;
}

export interface EngineState {
  time: number;
  duration: number;
  paused: boolean;
  buffering: boolean;
  ended: boolean;
  error: string | null;
  errorCode: 'unsupported' | 'network' | 'other' | null;
  audio: Track[];
  audioSel: string | null;
  text: Track[];
  textSel: string | null; // null = off
  rate: number;
  /** Subtitle text for engines that don't render it themselves (AVPlay). */
  cue?: string;
  /** Picture-in-picture / AirPlay (Safari, Chrome) — false where unsupported (e.g. Samsung AVPlay). */
  pipAvailable?: boolean;
  inPip?: boolean;
  airplayAvailable?: boolean;
  /** Coded picture size, once known. */
  vw: number;
  vh: number;
}

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface LoadOpts {
  startAt?: number;
  live?: boolean;
}

export interface Engine {
  readonly name: 'html' | 'avplay';
  state: EngineState;
  load(url: string, opts: LoadOpts): Promise<void>;
  play(): void;
  pause(): void;
  toggle(): void;
  seek(t: number): void;
  setRate(r: number): void;
  setAudio(id: string): void;
  setText(id: string | null): void;
  onChange(fn: (s: EngineState) => void): () => void;
  /** Place the full video frame at this rectangle (container pixels; may extend off-screen). */
  setFrame(r: Rect, container: { w: number; h: number }): void;
  /** Element frames can be drawn from for black-bar detection (null if not possible). */
  frameSource(): HTMLVideoElement | null;
  togglePip?(): void;
  showAirplay?(): void;
  destroy(): void;
}

const initial = (): EngineState => ({
  time: 0,
  duration: 0,
  paused: true,
  buffering: true,
  ended: false,
  error: null,
  errorCode: null,
  audio: [],
  audioSel: null,
  text: [],
  textSel: null,
  rate: 1,
  vw: 0,
  vh: 0,
});

abstract class Base {
  state = initial();
  private fns = new Set<(s: EngineState) => void>();
  onChange(fn: (s: EngineState) => void) {
    this.fns.add(fn);
    return () => {
      this.fns.delete(fn);
    };
  }
  protected set(p: Partial<EngineState>) {
    this.state = { ...this.state, ...p };
    this.fns.forEach((f) => f(this.state));
  }
  toggle() {
    if (this.state.paused || this.state.ended) this.play();
    else this.pause();
  }
  abstract play(): void;
  abstract pause(): void;
}

const isHlsUrl = (u: string) => /\.m3u8(\?|$)/i.test(u);

export class HtmlEngine extends Base implements Engine {
  readonly name = 'html' as const;
  video: HTMLVideoElement;
  private hls: any = null;
  private offs: (() => void)[] = [];

  constructor(container: HTMLElement) {
    super();
    const v = document.createElement('video');
    v.className = 'video';
    v.playsInline = true;
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', '');
    // iPhone/Mac: allow AirPlay and slip into picture-in-picture when you leave the app mid-video.
    v.setAttribute('x-webkit-airplay', 'allow');
    v.setAttribute('autopictureinpicture', '');
    v.preload = 'auto';
    container.appendChild(v);
    this.video = v;
    const on = (ev: string, fn: () => void) => {
      v.addEventListener(ev, fn);
      this.offs.push(() => v.removeEventListener(ev, fn));
    };
    on('timeupdate', () => this.set({ time: v.currentTime }));
    on('durationchange', () => this.set({ duration: isFinite(v.duration) ? v.duration : 0 }));
    on('loadedmetadata', () => {
      this.set({ duration: isFinite(v.duration) ? v.duration : 0, vw: v.videoWidth, vh: v.videoHeight });
      this.readTracks();
    });
    on('resize', () => this.set({ vw: v.videoWidth, vh: v.videoHeight }));
    on('play', () => this.set({ paused: false, ended: false }));
    on('pause', () => this.set({ paused: true }));
    on('waiting', () => this.set({ buffering: true }));
    on('playing', () => this.set({ buffering: false, paused: false }));
    on('canplay', () => this.set({ buffering: false }));
    on('seeked', () => this.set({ time: v.currentTime }));
    on('ended', () => this.set({ ended: true, paused: true }));
    on('ratechange', () => this.set({ rate: v.playbackRate }));
    const vx = v as any;
    const pipOk = () =>
      (!!(document as any).pictureInPictureEnabled && typeof vx.requestPictureInPicture === 'function' && !vx.disablePictureInPicture) ||
      (typeof vx.webkitSupportsPresentationMode === 'function' && vx.webkitSupportsPresentationMode('picture-in-picture'));
    this.set({ pipAvailable: pipOk() });
    on('loadedmetadata', () => this.set({ pipAvailable: pipOk() }));
    on('enterpictureinpicture', () => this.set({ inPip: true }));
    on('leavepictureinpicture', () => this.set({ inPip: false }));
    on('webkitpresentationmodechanged', () => this.set({ inPip: vx.webkitPresentationMode === 'picture-in-picture' }));
    const air = (e: any) => this.set({ airplayAvailable: e.availability === 'available' });
    v.addEventListener('webkitplaybacktargetavailabilitychanged', air);
    this.offs.push(() => v.removeEventListener('webkitplaybacktargetavailabilitychanged', air));
    on('error', () => {
      const code = v.error?.code;
      // 4 = MEDIA_ERR_SRC_NOT_SUPPORTED, 3 = DECODE, 2 = NETWORK
      this.set({
        error: code === 4 || code === 3 ? 'This device cannot play this file format.' : code === 2 ? 'Network error while playing.' : 'Playback failed.',
        errorCode: code === 4 || code === 3 ? 'unsupported' : code === 2 ? 'network' : 'other',
        buffering: false,
      });
    });
    const tt = v.textTracks as any;
    if (tt?.addEventListener) {
      const f = () => this.readTracks();
      tt.addEventListener('addtrack', f);
      this.offs.push(() => tt.removeEventListener('addtrack', f));
    }
  }

  async load(url: string, { startAt = 0, live = false }: LoadOpts) {
    const v = this.video;
    this.set({ ...initial(), buffering: true });
    const native = v.canPlayType('application/vnd.apple.mpegurl') !== '';
    if (isHlsUrl(url) && !native) {
      const { default: Hls } = await import('hls.js');
      if (Hls.isSupported()) {
        const hls = new Hls({ startPosition: startAt > 0 ? startAt : -1, enableWorker: true, lowLatencyMode: live });
        this.hls = hls;
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          this.readTracks();
          v.play().catch(() => this.set({ paused: true }));
        });
        hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => this.readTracks());
        hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, () => this.readTracks());
        hls.on(Hls.Events.ERROR, (_e: unknown, d: any) => {
          if (!d.fatal) return;
          if (d.type === Hls.ErrorTypes.NETWORK_ERROR) {
            this.set({ error: 'Network error while playing.', errorCode: 'network' });
          } else if (d.type === Hls.ErrorTypes.MEDIA_ERROR) {
            hls.recoverMediaError();
          } else {
            this.set({ error: 'Playback failed.', errorCode: 'other' });
          }
        });
        hls.loadSource(url);
        hls.attachMedia(v);
        return;
      }
    }
    v.src = url;
    if (startAt > 0) {
      const seekOnce = () => {
        try {
          v.currentTime = startAt;
        } catch {
          /* ignore */
        }
      };
      v.addEventListener('loadedmetadata', seekOnce, { once: true });
    }
    v.play().catch((e) => {
      // Autoplay with sound may be blocked until the user taps play.
      if (e?.name === 'NotAllowedError') this.set({ paused: true, buffering: false });
    });
  }

  private readTracks() {
    const v = this.video as any;
    let audio: Track[] = [];
    let audioSel: string | null = null;
    if (this.hls) {
      audio = this.hls.audioTracks.map((t: any, i: number) => ({ id: String(i), label: t.name || t.lang || `Track ${i + 1}`, lang: t.lang }));
      audioSel = String(this.hls.audioTrack);
    } else if (v.audioTracks) {
      for (let i = 0; i < v.audioTracks.length; i++) {
        const t = v.audioTracks[i];
        audio.push({ id: String(i), label: t.label || t.language || `Track ${i + 1}`, lang: t.language });
        if (t.enabled) audioSel = String(i);
      }
    }
    const text: Track[] = [];
    let textSel: string | null = null;
    for (let i = 0; i < v.textTracks.length; i++) {
      const t = v.textTracks[i];
      if (t.kind !== 'subtitles' && t.kind !== 'captions') continue;
      text.push({ id: String(i), label: t.label || t.language || `Subtitles ${i + 1}`, lang: t.language });
      if (t.mode === 'showing') textSel = String(i);
    }
    this.set({ audio, audioSel, text, textSel });
  }

  play() {
    if (this.state.ended) this.video.currentTime = 0;
    this.video.play().catch(() => {});
  }
  pause() {
    this.video.pause();
  }
  seek(t: number) {
    const d = this.state.duration || this.video.duration;
    this.video.currentTime = Math.max(0, isFinite(d) && d > 0 ? Math.min(t, d - 0.5) : t);
    this.set({ time: this.video.currentTime, ended: false });
  }
  setRate(r: number) {
    this.video.playbackRate = r;
  }
  setFrame(r: Rect) {
    const st = this.video.style;
    st.position = 'absolute';
    st.left = `${r.x}px`;
    st.top = `${r.y}px`;
    st.width = `${r.w}px`;
    st.height = `${r.h}px`;
    st.objectFit = 'fill';
  }
  frameSource() {
    return this.video;
  }
  togglePip() {
    const v = this.video as any;
    const d = document as any;
    if (d.pictureInPictureElement === v) d.exitPictureInPicture?.().catch(() => {});
    else if (typeof v.requestPictureInPicture === 'function' && d.pictureInPictureEnabled) v.requestPictureInPicture().catch(() => this.webkitPip());
    else this.webkitPip();
  }
  private webkitPip() {
    const v = this.video as any;
    if (typeof v.webkitSetPresentationMode === 'function') {
      v.webkitSetPresentationMode(v.webkitPresentationMode === 'picture-in-picture' ? 'inline' : 'picture-in-picture');
    }
  }
  showAirplay() {
    (this.video as any).webkitShowPlaybackTargetPicker?.();
  }
  setAudio(id: string) {
    const v = this.video as any;
    if (this.hls) this.hls.audioTrack = Number(id);
    else if (v.audioTracks) for (let i = 0; i < v.audioTracks.length; i++) v.audioTracks[i].enabled = String(i) === id;
    this.readTracks();
  }
  setText(id: string | null) {
    const tt = this.video.textTracks;
    for (let i = 0; i < tt.length; i++) tt[i].mode = String(i) === id ? 'showing' : 'disabled';
    this.readTracks();
  }
  destroy() {
    this.offs.forEach((f) => f());
    this.hls?.destroy();
    this.video.pause();
    this.video.removeAttribute('src');
    this.video.load();
    this.video.remove();
  }
}

/** Samsung Tizen native player. Spec: developer.samsung.com/smarttv/develop/api-references/samsung-product-api-references/avplay-api.html */
export class AvPlayEngine extends Base implements Engine {
  readonly name = 'avplay' as const;
  private obj: HTMLObjectElement;
  private av: any;
  private poll: ReturnType<typeof setInterval> | null = null;

  static available() {
    return !!window.webapis?.avplay;
  }

  constructor(container: HTMLElement) {
    super();
    this.av = window.webapis.avplay;
    const o = document.createElement('object');
    o.type = 'application/avplayer';
    o.className = 'video';
    container.appendChild(o);
    this.obj = o;
  }

  async load(url: string, { startAt = 0 }: LoadOpts) {
    const av = this.av;
    this.set({ ...initial(), buffering: true });
    try {
      av.stop();
      av.close();
    } catch {
      /* first load */
    }
    av.open(url);
    av.setDisplayRect(0, 0, 1920, 1080);
    try {
      // We compute the exact rectangle ourselves (aspect/zoom/crop), so stretch into it.
      av.setDisplayMethod('PLAYER_DISPLAY_MODE_FULL_SCREEN');
    } catch {
      /* older firmware */
    }
    av.setListener({
      onbufferingstart: () => this.set({ buffering: true }),
      onbufferingcomplete: () => this.set({ buffering: false }),
      oncurrentplaytime: (ms: number) => this.set({ time: ms / 1000 }),
      onstreamcompleted: () => {
        this.set({ ended: true, paused: true });
        try {
          av.stop();
        } catch {
          /* ignore */
        }
      },
      onsubtitlechange: (_dur: number, text: string) => this.set({ cue: text || undefined }),
      onerror: (e: unknown) => this.set({ error: `Player error: ${String(e)}`, errorCode: 'other', buffering: false }),
    });
    await new Promise<void>((resolve) => {
      av.prepareAsync(
        () => {
          this.set({ duration: (av.getDuration() || 0) / 1000, ...this.videoSize() });
          this.readTracks();
          if (startAt > 0) av.seekTo(Math.floor(startAt * 1000));
          av.play();
          this.set({ paused: false, buffering: false });
          resolve();
        },
        (err: unknown) => {
          this.set({ error: `Cannot open stream: ${String(err)}`, errorCode: 'unsupported', buffering: false });
          resolve();
        },
      );
    });
    this.poll = setInterval(() => {
      try {
        const st = av.getState();
        this.set({ paused: st !== 'PLAYING' });
      } catch {
        /* closed */
      }
    }, 1000);
  }

  private videoSize(): { vw: number; vh: number } {
    try {
      const info = (this.av.getCurrentStreamInfo() as any[]).find((t) => t.type === 'VIDEO');
      const x = JSON.parse(info?.extra_info || '{}');
      return { vw: Number(x.Width) || 0, vh: Number(x.Height) || 0 };
    } catch {
      return { vw: 0, vh: 0 };
    }
  }

  setFrame(r: Rect, container: { w: number; h: number }) {
    // AVPlay coordinates are on a 1920×1080 canvas regardless of the panel resolution.
    const sx = 1920 / (container.w || 1920);
    const sy = 1080 / (container.h || 1080);
    const rect = [Math.round(r.x * sx), Math.round(r.y * sy), Math.round(r.w * sx), Math.round(r.h * sy)];
    try {
      this.av.setDisplayRect(...rect);
    } catch {
      // Some firmware rejects off-screen rectangles: clamp (loses crop/zoom beyond the edges).
      const x = Math.max(0, rect[0]);
      const y = Math.max(0, rect[1]);
      try {
        this.av.setDisplayRect(x, y, Math.min(1920 - x, rect[2]), Math.min(1080 - y, rect[3]));
      } catch {
        /* ignore */
      }
    }
  }

  frameSource() {
    return null; // AVPlay renders on a hardware plane; frames are not readable from JS
  }

  private readTracks() {
    try {
      const all = this.av.getTotalTrackInfo() as any[];
      const audio: Track[] = [];
      const text: Track[] = [];
      for (const t of all) {
        let extra: any = {};
        try {
          extra = JSON.parse(t.extra_info || '{}');
        } catch {
          /* ignore */
        }
        const label = extra.language || extra.track_lang || `Track ${t.index}`;
        if (t.type === 'AUDIO') audio.push({ id: String(t.index), label, lang: extra.language });
        if (t.type === 'TEXT') text.push({ id: String(t.index), label, lang: extra.track_lang });
      }
      this.set({ audio, text, audioSel: audio[0]?.id ?? null });
    } catch {
      /* not available before prepare */
    }
  }

  play() {
    try {
      if (this.state.ended) {
        this.av.prepare();
        this.av.seekTo(0);
      }
      this.av.play();
      this.set({ paused: false, ended: false });
    } catch {
      /* ignore */
    }
  }
  pause() {
    try {
      this.av.pause();
      this.set({ paused: true });
    } catch {
      /* ignore */
    }
  }
  seek(t: number) {
    try {
      this.av.seekTo(Math.max(0, Math.floor(t * 1000)));
      this.set({ time: t });
    } catch {
      /* ignore */
    }
  }
  setRate(r: number) {
    try {
      this.av.setSpeed(r);
      this.set({ rate: r });
    } catch {
      /* fractional speeds unsupported on some models */
    }
  }
  setAudio(id: string) {
    try {
      this.av.setSelectTrack('AUDIO', Number(id));
      this.set({ audioSel: id });
    } catch {
      /* ignore */
    }
  }
  setText(id: string | null) {
    try {
      if (id === null) this.av.setSilentSubtitle(true);
      else {
        this.av.setSilentSubtitle(false);
        this.av.setSelectTrack('TEXT', Number(id));
      }
      this.set({ textSel: id });
    } catch {
      /* ignore */
    }
  }
  destroy() {
    if (this.poll) clearInterval(this.poll);
    try {
      this.av.stop();
      this.av.close();
    } catch {
      /* ignore */
    }
    this.obj.remove();
  }
}
