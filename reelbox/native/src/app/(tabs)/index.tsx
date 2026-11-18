import { useMemo } from 'react';
import { Pressable, ScrollView, Text, useWindowDimensions, View } from 'react-native';
import { router } from 'expo-router';
import { useNavigation } from 'expo-router';
import { useEffect } from 'react';
import { useSession } from '@shared/session';
import { useCloud } from '@shared/cloud';
import { continueWatching, useFavorites, useProgressMap, fmtTime } from '@shared/userdata';
import { sortItems, useVodStreams, useSeriesList } from '@shared/catalog';
import { Poster } from '@/components/Poster';
import { s } from '@/components/ui';
import { openMovie, openSeries, play } from '@/lib/nav';
import { C } from '@/theme';

function HeaderButtons() {
  return (
    <View style={[s.row, { marginRight: 12, gap: 18 }]}>
      <Pressable onPress={() => router.push('/search')} accessibilityLabel="Search" hitSlop={10}>
        <Text style={{ color: C.text, fontSize: 20 }}>⌕</Text>
      </Pressable>
      <Pressable onPress={() => router.push('/settings')} accessibilityLabel="Settings" hitSlop={10}>
        <Text style={{ color: C.text, fontSize: 20 }}>⚙︎</Text>
      </Pressable>
    </View>
  );
}

function Row({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={{ gap: 10 }}>
      <Text style={[s.h2, { paddingHorizontal: 16 }]}>{title}</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 12, paddingHorizontal: 16 }}>
        {children}
      </ScrollView>
    </View>
  );
}

export default function Home() {
  const nav = useNavigation();
  useEffect(() => {
    nav.setOptions({ headerRight: () => <HeaderButtons />, headerTitle: 'Reelbox' });
  }, [nav]);
  const { account, online } = useSession();
  const acc = account!.id;
  const progress = useProgressMap(acc);
  const cont = useMemo(() => continueWatching(progress).slice(0, 20), [progress]);
  const { favs } = useFavorites(acc);
  const favList = useMemo(() => Object.values(favs).sort((a, b) => b.at - a.at), [favs]);
  const vod = useVodStreams();
  const ser = useSeriesList();
  const newMovies = useMemo(() => sortItems(vod.data ?? [], 'recent').slice(0, 24), [vod.data]);
  const newSeries = useMemo(() => sortItems(ser.data ?? [], 'recent').slice(0, 24), [ser.data]);
  const cloud = useCloud();
  const { width } = useWindowDimensions();
  const w = Math.min(150, (width - 16 * 2 - 12 * 2) / 2.6);

  return (
    <ScrollView contentContainerStyle={{ paddingVertical: 16, gap: 24 }}>
      {!online ? (
        <Text style={[s.card, s.muted, { marginHorizontal: 16 }]}>Offline — showing saved catalog and downloads.</Text>
      ) : null}
      {cloud.error ? <Text style={[s.card, { color: C.danger, marginHorizontal: 16 }]}>Family sync: {cloud.error}</Text> : null}
      {cont.length > 0 && (
        <Row title="Continue watching">
          {cont.map((p) => (
            <Poster
              key={`${p.ref.kind}:${p.ref.id}`}
              width={w * 1.5}
              wide
              title={p.ref.seriesName ? `${p.ref.seriesName} · S${p.ref.season}E${p.ref.episode}` : p.ref.name}
              image={p.ref.poster}
              progress={p.pos / p.dur}
              badge={fmtTime(p.dur - p.pos) + ' left'}
              onPress={() => play(p.ref)}
            />
          ))}
        </Row>
      )}
      {favList.length > 0 && (
        <Row title="Favorites">
          {favList.map((f) => (
            <Poster
              key={`${f.kind}:${f.id}`}
              width={w}
              title={f.name}
              image={f.poster}
              onPress={() =>
                f.kind === 'movie' ? openMovie(f.id) : f.kind === 'series' ? openSeries(f.id) : play({ kind: 'live', id: f.id, name: f.name, poster: f.poster })
              }
            />
          ))}
        </Row>
      )}
      {newMovies.length > 0 && (
        <Row title="New movies">
          {newMovies.map((m) => (
            <Poster key={m.stream_id} width={w} title={m.name} image={m.stream_icon} onPress={() => openMovie(m.stream_id)} />
          ))}
        </Row>
      )}
      {newSeries.length > 0 && (
        <Row title="New series">
          {newSeries.map((m) => (
            <Poster key={m.series_id} width={w} title={m.name} image={m.cover} onPress={() => openSeries(m.series_id)} />
          ))}
        </Row>
      )}
      {vod.loading && !vod.data ? <Text style={[s.muted, { paddingHorizontal: 16 }]}>Loading the catalog…</Text> : null}
    </ScrollView>
  );
}

