import { useEffect, useState, type ReactNode } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View, type TextInputProps, type ViewStyle } from 'react-native';
import { C, R } from '@/theme';

export function Button({
  title,
  onPress,
  kind = 'default',
  small,
  disabled,
  style,
  busy,
}: {
  title: string;
  onPress?: () => void;
  kind?: 'default' | 'primary' | 'ghost' | 'danger';
  small?: boolean;
  disabled?: boolean;
  busy?: boolean;
  style?: ViewStyle;
}) {
  const bg = kind === 'primary' ? C.accent : kind === 'ghost' ? 'transparent' : C.bg3;
  const fg = kind === 'primary' ? C.accentInk : kind === 'danger' ? C.danger : C.text;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={title}
      onPress={disabled || busy ? undefined : onPress}
      style={({ pressed }) => [
        s.btn,
        small && s.btnSmall,
        { backgroundColor: bg, opacity: disabled ? 0.45 : pressed ? 0.75 : 1 },
        kind === 'ghost' && { borderWidth: 1, borderColor: C.line },
        style,
      ]}
    >
      {busy ? <ActivityIndicator color={fg} /> : <Text style={[s.btnText, small && { fontSize: 14 }, { color: fg }]}>{title}</Text>}
    </Pressable>
  );
}

export function Chip({ label, active, onPress }: { label: string; active?: boolean; onPress?: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: !!active }}
      onPress={onPress}
      style={[s.chip, active && { backgroundColor: C.accent, borderColor: C.accent }]}
    >
      <Text style={[s.chipText, active && { color: C.accentInk }]} numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
}

export function Field({ label, ...props }: TextInputProps & { label: string }) {
  return (
    <View style={{ gap: 6 }}>
      <Text style={s.label}>{label}</Text>
      <TextInput
        placeholderTextColor={C.muted}
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel={label}
        {...props}
        style={[s.input, props.style]}
      />
    </View>
  );
}

export const Loading = ({ label }: { label?: string }) => (
  <View style={s.center}>
    <ActivityIndicator color={C.accent} size="large" />
    {label ? <Text style={s.muted}>{label}</Text> : null}
  </View>
);

export const Empty = ({ title, children }: { title: string; children?: ReactNode }) => (
  <View style={s.center}>
    <Text style={s.h2}>{title}</Text>
    {typeof children === 'string' ? <Text style={[s.muted, { textAlign: 'center' }]}>{children}</Text> : children}
  </View>
);

export function ErrorBox({ error, onRetry }: { error: Error | string; onRetry?: () => void }) {
  return (
    <View style={[s.card, { borderColor: C.danger, gap: 10 }]}>
      <Text style={{ color: C.danger, fontWeight: '600' }}>{typeof error === 'string' ? error : error.message}</Text>
      {onRetry ? <Button small title="Try again" onPress={onRetry} /> : null}
    </View>
  );
}

// ---- toast ----
let showToast: ((m: string) => void) | null = null;
export const toast = (m: string) => showToast?.(m);
export function ToastHost() {
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => {
    let t: ReturnType<typeof setTimeout>;
    showToast = (m) => {
      setMsg(m);
      clearTimeout(t);
      t = setTimeout(() => setMsg(null), 2600);
    };
    return () => {
      showToast = null;
      clearTimeout(t);
    };
  }, []);
  if (!msg) return null;
  return (
    <View pointerEvents="none" style={s.toastWrap}>
      <Text style={s.toast} accessibilityLiveRegion="polite">
        {msg}
      </Text>
    </View>
  );
}

export const s = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.bg },
  pad: { padding: 16, gap: 16 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 12 },
  h1: { color: C.text, fontSize: 26, fontWeight: '700' },
  h2: { color: C.text, fontSize: 19, fontWeight: '700' },
  h3: { color: C.text, fontSize: 16, fontWeight: '600' },
  text: { color: C.text, fontSize: 15, lineHeight: 21 },
  muted: { color: C.muted, fontSize: 14, lineHeight: 19 },
  label: { color: C.muted, fontSize: 13, fontWeight: '600' },
  card: { backgroundColor: C.bg2, borderRadius: R, borderWidth: 1, borderColor: C.line, padding: 14 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  input: {
    backgroundColor: C.bg3,
    color: C.text,
    borderRadius: R,
    borderWidth: 1,
    borderColor: C.line,
    paddingHorizontal: 12,
    paddingVertical: 11,
    fontSize: 16,
  },
  btn: { minHeight: 46, paddingHorizontal: 16, borderRadius: R, alignItems: 'center', justifyContent: 'center' },
  btnSmall: { minHeight: 36, paddingHorizontal: 12 },
  btnText: { fontSize: 16, fontWeight: '600' },
  chip: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 99, borderWidth: 1, borderColor: C.line, backgroundColor: C.bg2, maxWidth: 220 },
  chipText: { color: C.text, fontSize: 14 },
  toastWrap: { position: 'absolute', left: 16, right: 16, bottom: 100, alignItems: 'center' },
  toast: { backgroundColor: '#000d', color: C.text, paddingHorizontal: 14, paddingVertical: 10, borderRadius: R, overflow: 'hidden' },
});
