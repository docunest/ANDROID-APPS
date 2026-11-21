// Picture controls for old videos: fit, forced shape, zoom, move, and cutting baked-in black bars
// (e.g. "double boxed" rips: a 16:9 film letterboxed into 4:3, then pillarboxed back to 16:9).
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { ASPECTS, ZOOM_MAX, ZOOM_MIN, type Crop, type Fit, type Picture } from '@shared/picture';
import { Button, Chip, s } from './ui';
import { C } from '@/theme';

const FITS: { k: Fit; label: string }[] = [
  { k: 'contain', label: 'Fit' },
  { k: 'cover', label: 'Fill screen' },
  { k: 'fill', label: 'Stretch' },
];

// Symmetric bar presets (fractions of the full frame).
const BAR_PRESETS: { label: string; tb: number; lr: number }[] = [
  { label: 'None', tb: 0, lr: 0 },
  { label: 'Side bars (4:3 in 16:9)', tb: 0, lr: 0.125 },
  { label: 'Top/bottom (16:9 in 4:3)', tb: 0.125, lr: 0 },
  { label: 'Both (double boxed)', tb: 0.125, lr: 0.125 },
  { label: 'Wide film (2.39 in 16:9)', tb: 0.128, lr: 0 },
];

const barsOf = (c: Crop | null) => ({ tb: c ? Math.round(((c.t + 1 - c.b) / 2) * 1000) / 1000 : 0, lr: c ? Math.round(((c.l + 1 - c.r) / 2) * 1000) / 1000 : 0 });
const cropOf = (tb: number, lr: number): Crop | null => (tb <= 0.001 && lr <= 0.001 ? null : { l: lr, r: 1 - lr, t: tb, b: 1 - tb });
const clamp = (v: number, a: number, b: number) => Math.min(b, Math.max(a, v));

export function PicturePanel({
  visible,
  onClose,
  picture,
  custom,
  scopeLabel,
  set,
  reset,
  makeDefault,
}: {
  visible: boolean;
  onClose: () => void;
  picture: Picture;
  custom: boolean;
  scopeLabel: string;
  set: (p: Partial<Picture>) => void;
  reset: () => void;
  makeDefault: () => void;
}) {
  const { tb, lr } = barsOf(picture.crop);
  const step = (v: number) => set({ zoom: clamp(Math.round((picture.zoom + v) * 100) / 100, ZOOM_MIN, ZOOM_MAX) });
  const move = (dx: number, dy: number) => set({ panX: clamp(picture.panX + dx, -1, 1), panY: clamp(picture.panY + dy, -1, 1) });
  const bars = (ntb: number, nlr: number) => set({ crop: cropOf(clamp(ntb, 0, 0.35), clamp(nlr, 0, 0.35)) });
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose} supportedOrientations={['portrait', 'landscape', 'landscape-left', 'landscape-right']}>
      <Pressable style={{ flex: 1 }} onPress={onClose} accessibilityLabel="Close picture settings" />
      <View style={{ backgroundColor: C.bg2, borderTopLeftRadius: 16, borderTopRightRadius: 16, maxHeight: '75%', borderTopWidth: 1, borderColor: C.line }}>
        <ScrollView contentContainerStyle={{ padding: 16, gap: 14 }}>
          <View style={[s.row, { justifyContent: 'space-between' }]}>
            <Text style={s.h2}>Picture</Text>
            <Button small title="Done" kind="primary" onPress={onClose} />
          </View>
          <Text style={s.muted}>Saved for {scopeLabel}. Pinch the video to zoom, drag to move, double-tap to switch Fit/Fill.</Text>

          <Text style={s.label}>FIT</Text>
          <View style={[s.row, { flexWrap: 'wrap', gap: 8 }]}>
            {FITS.map((f) => (
              <Chip key={f.k} label={f.label} active={picture.fit === f.k} onPress={() => set({ fit: f.k })} />
            ))}
          </View>

          <Text style={s.label}>SHAPE (fixes squashed or stretched video)</Text>
          <View style={[s.row, { flexWrap: 'wrap', gap: 8 }]}>
            {ASPECTS.map((a) => (
              <Chip
                key={a.label}
                label={a.label}
                active={a.value === null ? picture.aspect === null : picture.aspect !== null && Math.abs(picture.aspect - a.value) < 0.01}
                onPress={() => set({ aspect: a.value })}
              />
            ))}
          </View>

          <Text style={s.label}>BLACK BARS IN THE VIDEO</Text>
          <View style={[s.row, { flexWrap: 'wrap', gap: 8 }]}>
            {BAR_PRESETS.map((b) => (
              <Chip key={b.label} label={b.label} active={Math.abs(tb - b.tb) < 0.002 && Math.abs(lr - b.lr) < 0.002} onPress={() => bars(b.tb, b.lr)} />
            ))}
          </View>
          <Stepper label={`Cut top & bottom ${Math.round(tb * 100)}%`} onMinus={() => bars(tb - 0.01, lr)} onPlus={() => bars(tb + 0.01, lr)} />
          <Stepper label={`Cut sides ${Math.round(lr * 100)}%`} onMinus={() => bars(tb, lr - 0.01)} onPlus={() => bars(tb, lr + 0.01)} />

          <Text style={s.label}>ZOOM</Text>
          <Stepper label={`${Math.round(picture.zoom * 100)}%`} onMinus={() => step(-0.05)} onPlus={() => step(0.05)} extra={<Button small title="100%" onPress={() => set({ zoom: 1, panX: 0, panY: 0 })} />} />

          <Text style={s.label}>MOVE (when zoomed in)</Text>
          <View style={[s.row, { gap: 8 }]}>
            <Button small title="◀" onPress={() => move(-0.2, 0)} />
            <Button small title="▲" onPress={() => move(0, -0.2)} />
            <Button small title="▼" onPress={() => move(0, 0.2)} />
            <Button small title="▶" onPress={() => move(0.2, 0)} />
            <Button small title="Center" onPress={() => set({ panX: 0, panY: 0 })} />
          </View>

          <View style={[s.row, { flexWrap: 'wrap', gap: 8, marginTop: 4 }]}>
            <Button small title="Use for all videos" onPress={makeDefault} />
            {custom ? <Button small kind="ghost" title={`Reset ${scopeLabel}`} onPress={reset} /> : null}
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
}

function Stepper({ label, onMinus, onPlus, extra }: { label: string; onMinus: () => void; onPlus: () => void; extra?: React.ReactNode }) {
  return (
    <View style={[s.row, { gap: 8 }]}>
      <Button small title="−" onPress={onMinus} style={{ width: 48 }} />
      <Text style={[s.text, { minWidth: 150, textAlign: 'center' }]}>{label}</Text>
      <Button small title="+" onPress={onPlus} style={{ width: 48 }} />
      {extra}
    </View>
  );
}
