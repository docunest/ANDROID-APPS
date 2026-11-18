import { Image } from 'expo-image';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { C, R } from '@/theme';

export function Poster({
  title,
  image,
  width,
  onPress,
  progress,
  badge,
  wide,
}: {
  title: string;
  image?: string;
  width: number;
  onPress: () => void;
  progress?: number; // 0..1
  badge?: string;
  wide?: boolean;
}) {
  const h = wide ? width * 0.5625 : width * 1.5;
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={title} style={({ pressed }) => [{ width, opacity: pressed ? 0.7 : 1 }]}>
      <View style={[st.box, { height: h }]}>
        {image ? (
          <Image source={{ uri: image }} style={StyleSheet.absoluteFill} contentFit="cover" transition={150} cachePolicy="disk" recyclingKey={image} />
        ) : (
          <Text style={st.fallback} numberOfLines={4}>
            {title}
          </Text>
        )}
        {badge ? <Text style={st.badge}>{badge}</Text> : null}
        {progress != null && progress > 0 ? (
          <View style={st.track}>
            <View style={[st.bar, { width: `${Math.min(100, progress * 100)}%` }]} />
          </View>
        ) : null}
      </View>
      <Text style={st.title} numberOfLines={2}>
        {title}
      </Text>
    </Pressable>
  );
}

const st = StyleSheet.create({
  box: { borderRadius: R, overflow: 'hidden', backgroundColor: C.bg3, justifyContent: 'center' },
  fallback: { color: C.muted, textAlign: 'center', padding: 8, fontSize: 13 },
  title: { color: C.text, fontSize: 13, marginTop: 6, lineHeight: 17 },
  badge: { position: 'absolute', top: 6, right: 6, backgroundColor: '#000b', color: C.text, fontSize: 11, paddingHorizontal: 6, paddingVertical: 2, borderRadius: 6, overflow: 'hidden' },
  track: { position: 'absolute', left: 0, right: 0, bottom: 0, height: 4, backgroundColor: '#0008' },
  bar: { height: 4, backgroundColor: C.accent },
});
