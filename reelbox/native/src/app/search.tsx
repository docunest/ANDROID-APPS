import { useMemo, useState } from 'react';
import { SectionList, Pressable, Text, TextInput, View } from 'react-native';
import { matches, useLiveStreams, useSeriesList, useVodStreams } from '@shared/catalog';
import { liveRef } from '@shared/xtream';
import { requireAccount } from '@/components/RequireAccount';
import { s } from '@/components/ui';
import { openMovie, openSeries, play } from '@/lib/nav';
import { C } from '@/theme';

type Hit = { key: string; name: string; go: () => void };

export default requireAccount(Search);

function Search() {
  const [q, setQ] = useState('');
  const vod = useVodStreams();
  const ser = useSeriesList();
  const live = useLiveStreams();
  const sections = useMemo(() => {
    if (q.trim().length < 2) return [];
    const cap = <T,>(a: T[]) => a.slice(0, 60);
    const m: Hit[] = cap((vod.data ?? []).filter((i) => matches(i.name, q))).map((i) => ({ key: `m${i.stream_id}`, name: i.name, go: () => openMovie(i.stream_id) }));
    const sr: Hit[] = cap((ser.data ?? []).filter((i) => matches(i.name, q))).map((i) => ({ key: `s${i.series_id}`, name: i.name, go: () => openSeries(i.series_id) }));
    const l: Hit[] = cap((live.data ?? []).filter((i) => matches(i.name, q))).map((i) => ({ key: `l${i.stream_id}`, name: i.name, go: () => play(liveRef(i)) }));
    return [
      { title: 'Movies', data: m },
      { title: 'Series', data: sr },
      { title: 'Live', data: l },
    ].filter((x) => x.data.length);
  }, [q, vod.data, ser.data, live.data]);
  return (
    <View style={{ flex: 1 }}>
      <View style={{ padding: 16 }}>
        <TextInput
          autoFocus
          value={q}
          onChangeText={setQ}
          placeholder="Movies, series, channels"
          placeholderTextColor={C.muted}
          style={s.input}
          autoCorrect={false}
          clearButtonMode="while-editing"
          accessibilityLabel="Search everything"
        />
      </View>
      <SectionList
        sections={sections}
        keyExtractor={(h) => h.key}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 32 }}
        renderSectionHeader={({ section }) => <Text style={[s.label, { paddingVertical: 8, backgroundColor: C.bg }]}>{section.title.toUpperCase()}</Text>}
        renderItem={({ item }) => (
          <Pressable onPress={item.go} style={({ pressed }) => [{ paddingVertical: 12, borderBottomWidth: 1, borderColor: C.line, opacity: pressed ? 0.6 : 1 }]}>
            <Text style={s.text}>{item.name}</Text>
          </Pressable>
        )}
        ListEmptyComponent={q.trim().length >= 2 ? <Text style={[s.muted, { textAlign: 'center', marginTop: 32 }]}>Nothing found</Text> : undefined}
      />
    </View>
  );
}
