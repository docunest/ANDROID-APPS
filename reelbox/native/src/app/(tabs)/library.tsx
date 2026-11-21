import { useEffect, useMemo, useState } from 'react';
import { Alert, FlatList, Pressable, Text, View } from 'react-native';
import { useSession } from '@shared/session';
import { fmtTime, useFavorites, useMoments } from '@shared/userdata';
import { refKey } from '@shared/xtream';
import { Button, Chip, Empty, s } from '@/components/ui';
import { openMovie, openSeries, play } from '@/lib/nav';
import {
  fmtBytes,
  liveProgress,
  pauseDownload,
  removeDownload,
  resumeDownload,
  subscribeProgress,
  useDownloads,
  type Download,
} from '@/lib/downloads';
import { C } from '@/theme';

type Tab = 'favorites' | 'bookmarks' | 'downloads';

export default function Library() {
  const [tab, setTab] = useState<Tab>('favorites');
  return (
    <View style={{ flex: 1 }}>
      <View style={[s.row, { padding: 16, paddingBottom: 4, gap: 8 }]}>
        <Chip label="Favorites" active={tab === 'favorites'} onPress={() => setTab('favorites')} />
        <Chip label="Bookmarks" active={tab === 'bookmarks'} onPress={() => setTab('bookmarks')} />
        <Chip label="Downloads" active={tab === 'downloads'} onPress={() => setTab('downloads')} />
      </View>
      {tab === 'favorites' ? <Favorites /> : tab === 'bookmarks' ? <Bookmarks /> : <Downloads />}
    </View>
  );
}

const Item = ({ title, sub, onPress, right }: { title: string; sub?: string; onPress?: () => void; right?: React.ReactNode }) => (
  <Pressable onPress={onPress} accessibilityRole="button" style={({ pressed }) => [s.card, s.row, { opacity: pressed ? 0.7 : 1 }]}>
    <View style={{ flex: 1, gap: 2 }}>
      <Text style={s.h3} numberOfLines={2}>
        {title}
      </Text>
      {sub ? <Text style={s.muted}>{sub}</Text> : null}
    </View>
    {right}
  </Pressable>
);

function Favorites() {
  const { account } = useSession();
  const { favs, toggle } = useFavorites(account!.id);
  const list = useMemo(() => Object.values(favs).sort((a, b) => b.at - a.at), [favs]);
  return (
    <FlatList
      data={list}
      keyExtractor={(f) => `${f.kind}:${f.id}`}
      contentContainerStyle={{ padding: 16, gap: 8 }}
      ListEmptyComponent={<Empty title="No favorites yet">Tap ☆ on a movie, series or channel.</Empty>}
      renderItem={({ item: f }) => (
        <Item
          title={f.name}
          sub={f.kind === 'movie' ? 'Movie' : f.kind === 'series' ? 'Series' : 'Live channel'}
          onPress={() =>
            f.kind === 'movie' ? openMovie(f.id) : f.kind === 'series' ? openSeries(f.id) : play({ kind: 'live', id: f.id, name: f.name, poster: f.poster })
          }
          right={<Button small kind="ghost" title="Remove" onPress={() => toggle(f)} />}
        />
      )}
    />
  );
}

function Bookmarks() {
  const { account } = useSession();
  const { moments, remove } = useMoments(account!.id);
  return (
    <FlatList
      data={moments}
      keyExtractor={(m) => m.id}
      contentContainerStyle={{ padding: 16, gap: 8 }}
      ListEmptyComponent={<Empty title="No bookmarks yet">While watching, tap “Bookmark” to save the exact moment.</Empty>}
      renderItem={({ item: m }) => (
        <Item
          title={m.note}
          sub={`${m.ref.seriesName ? `${m.ref.seriesName} · ` : ''}${m.ref.name} · ${fmtTime(m.pos)}`}
          onPress={() => play(m.ref, { from: m.pos })}
          right={<Button small kind="ghost" title="Delete" onPress={() => remove(m.id)} />}
        />
      )}
    />
  );
}

function Downloads() {
  const map = useDownloads();
  const [, tick] = useState(0);
  useEffect(() => subscribeProgress(() => tick((n) => n + 1)), []);
  const list = useMemo(() => Object.values(map).sort((a, b) => b.at - a.at), [map]);
  const status = (d: Download) => {
    const lp = liveProgress(refKey(d.ref));
    const bytes = lp?.bytes ?? d.bytes;
    const total = lp?.total || d.total;
    const pct = total ? ` · ${Math.floor((bytes / total) * 100)}%` : '';
    switch (d.status) {
      case 'done':
        return `Saved · ${fmtBytes(d.bytes)}`;
      case 'downloading':
        return `Downloading ${fmtBytes(bytes)}${total ? ` of ${fmtBytes(total)}` : ''}${pct}`;
      case 'queued':
        return 'Waiting…';
      case 'paused':
        return `Paused${pct}`;
      case 'error':
        return `Failed: ${d.error ?? ''}`;
    }
  };
  return (
    <FlatList
      data={list}
      keyExtractor={(d) => refKey(d.ref)}
      contentContainerStyle={{ padding: 16, gap: 8 }}
      ListEmptyComponent={<Empty title="No downloads">Open a movie or episode and tap “Download” to watch it without internet.</Empty>}
      renderItem={({ item: d }) => {
        const key = refKey(d.ref);
        return (
          <Item
            title={d.ref.seriesName ? `${d.ref.seriesName} · S${d.ref.season}E${d.ref.episode} ${d.ref.name}` : d.ref.name}
            sub={status(d)}
            onPress={d.status === 'done' ? () => play(d.ref) : undefined}
            right={
              <View style={{ gap: 6 }}>
                {d.status === 'downloading' || d.status === 'queued' ? <Button small title="Pause" onPress={() => pauseDownload(key)} /> : null}
                {d.status === 'paused' || d.status === 'error' ? <Button small title="Resume" onPress={() => resumeDownload(key)} /> : null}
                <Button
                  small
                  kind="danger"
                  title="Delete"
                  onPress={() =>
                    Alert.alert('Delete download?', d.ref.name, [
                      { text: 'Cancel', style: 'cancel' },
                      { text: 'Delete', style: 'destructive', onPress: () => removeDownload(key) },
                    ])
                  }
                />
              </View>
            }
          />
        );
      }}
      ListFooterComponent={list.length ? <Text style={[s.muted, { marginTop: 8, color: C.muted }]}>Downloads continue while the app is open.</Text> : undefined}
    />
  );
}
