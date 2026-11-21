// Movies / Series / Live: category chips, search-as-you-type, sort, poster grid (or channel list).
import { useMemo, useState } from 'react';
import { FlatList, Pressable, RefreshControl, ScrollView, Text, TextInput, useWindowDimensions, View } from 'react-native';
import { Image } from 'expo-image';
import {
  byCategory,
  matches,
  sortItems,
  useLiveCategories,
  useLiveStreams,
  useSeriesCategories,
  useSeriesList,
  useVodCategories,
  useVodStreams,
  type SortKey,
} from '@shared/catalog';
import { useSession } from '@shared/session';
import { useFavorites, useProgressMap } from '@shared/userdata';
import { liveRef, rating10, type LiveStream, type SeriesItem, type VodStream } from '@shared/xtream';
import { Chip, Empty, ErrorBox, Loading, s } from './ui';
import { Poster } from './Poster';
import { openMovie, openSeries, play } from '@/lib/nav';
import { C, R } from '@/theme';

type Type = 'movies' | 'series' | 'live';
const SORTS: { k: SortKey; label: string }[] = [
  { k: 'recent', label: 'Newest' },
  { k: 'az', label: 'A–Z' },
  { k: 'rating', label: 'Rating' },
];

export function Browse({ type }: { type: Type }) {
  const { account } = useSession();
  const acc = account!.id;
  const vodCats = useVodCategories(type === 'movies');
  const vod = useVodStreams(type === 'movies');
  const serCats = useSeriesCategories(type === 'series');
  const ser = useSeriesList(type === 'series');
  const liveCats = useLiveCategories(type === 'live');
  const live = useLiveStreams(type === 'live');
  const cats = type === 'movies' ? vodCats : type === 'series' ? serCats : liveCats;
  const list = type === 'movies' ? vod : type === 'series' ? ser : live;
  const { favs } = useFavorites(acc);
  const progress = useProgressMap(acc);

  const [cat, setCat] = useState('all');
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<SortKey>(type === 'live' ? 'az' : 'recent');
  const { width } = useWindowDimensions();
  const cols = Math.max(3, Math.floor((width - 16) / 130));
  const cardW = (width - 16 * 2 - 12 * (cols - 1)) / cols;

  const favKind = type === 'movies' ? 'movie' : type === 'series' ? 'series' : 'live';
  const items = useMemo(() => {
    let all = (list.data ?? []) as (VodStream | SeriesItem | LiveStream)[];
    if (cat === 'fav') all = all.filter((i) => favs[`${favKind}:${'series_id' in i ? i.series_id : (i as VodStream).stream_id}`]);
    else all = byCategory(all, cat);
    if (q.trim()) all = all.filter((i) => matches(i.name, q));
    return type === 'live' && sort === 'recent' ? all : sortItems(all as any, sort);
  }, [list.data, cat, q, sort, favs, favKind, type]);

  if (list.loading && !list.data) return <Loading label="Loading the catalog…" />;
  if (list.error && !list.data)
    return (
      <View style={s.pad}>
        <ErrorBox error={list.error} onRetry={list.refresh} />
      </View>
    );

  const header = (
    <View style={{ gap: 10, paddingBottom: 12 }}>
      <TextInput
        value={q}
        onChangeText={setQ}
        placeholder={`Search ${type}`}
        placeholderTextColor={C.muted}
        style={s.input}
        clearButtonMode="while-editing"
        autoCorrect={false}
        accessibilityLabel={`Search ${type}`}
      />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
        <Chip label="All" active={cat === 'all'} onPress={() => setCat('all')} />
        <Chip label="★ Favorites" active={cat === 'fav'} onPress={() => setCat('fav')} />
        {(cats.data ?? []).map((c) => (
          <Chip key={c.category_id} label={c.category_name} active={cat === String(c.category_id)} onPress={() => setCat(String(c.category_id))} />
        ))}
      </ScrollView>
      <View style={[s.row, { justifyContent: 'space-between' }]}>
        <Text style={s.muted}>
          {items.length} {list.stale ? '· saved copy' : ''}
        </Text>
        <View style={[s.row, { gap: 6 }]}>
          {SORTS.map((o) => (
            <Chip key={o.k} label={o.label} active={sort === o.k} onPress={() => setSort(o.k)} />
          ))}
        </View>
      </View>
    </View>
  );

  const refresh = <RefreshControl refreshing={list.loading} onRefresh={list.refresh} tintColor={C.accent} />;

  if (type === 'live') {
    return (
      <FlatList
        data={items as LiveStream[]}
        keyExtractor={(i) => String(i.stream_id)}
        ListHeaderComponent={header}
        contentContainerStyle={{ padding: 16 }}
        refreshControl={refresh}
        ItemSeparatorComponent={() => <View style={{ height: 8 }} />}
        ListEmptyComponent={<Empty title="Nothing here" />}
        renderItem={({ item }) => (
          <Pressable
            onPress={() => play(liveRef(item))}
            accessibilityRole="button"
            accessibilityLabel={item.name}
            style={({ pressed }) => [s.card, s.row, { padding: 10, opacity: pressed ? 0.7 : 1 }]}
          >
            <View style={{ width: 56, height: 40, borderRadius: 6, backgroundColor: C.bg3, overflow: 'hidden' }}>
              {item.stream_icon ? <Image source={{ uri: item.stream_icon }} style={{ flex: 1 }} contentFit="contain" /> : null}
            </View>
            <Text style={[s.text, { flex: 1 }]} numberOfLines={2}>
              {item.name}
            </Text>
            {favs[`live:${item.stream_id}`] ? <Text style={{ color: C.accent }}>★</Text> : null}
          </Pressable>
        )}
      />
    );
  }

  return (
    <FlatList
      key={cols}
      data={items as (VodStream | SeriesItem)[]}
      numColumns={cols}
      keyExtractor={(i) => String('series_id' in i ? i.series_id : (i as VodStream).stream_id)}
      ListHeaderComponent={header}
      contentContainerStyle={{ padding: 16 }}
      columnWrapperStyle={{ gap: 12 }}
      ItemSeparatorComponent={() => <View style={{ height: 14 }} />}
      refreshControl={refresh}
      initialNumToRender={18}
      windowSize={7}
      ListEmptyComponent={<Empty title="Nothing here" />}
      renderItem={({ item }) => {
        const isSeries = 'series_id' in item;
        const id = isSeries ? (item as SeriesItem).series_id : (item as VodStream).stream_id;
        const p = !isSeries ? progress[`movie:${id}`] : undefined;
        const r = rating10(item);
        return (
          <Poster
            width={cardW}
            title={item.name}
            image={isSeries ? (item as SeriesItem).cover : (item as VodStream).stream_icon}
            badge={r ? `★ ${r}` : undefined}
            progress={p ? (p.watched ? 1 : p.pos / p.dur) : undefined}
            onPress={() => (isSeries ? openSeries(id) : openMovie(id))}
          />
        );
      }}
    />
  );
}

export const listStyle = { borderRadius: R };
