import { useEffect, useMemo } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { useLocalSearchParams, useNavigation } from 'expo-router';
import { useVodInfo, useVodStreams } from '@shared/catalog';
import { useSession } from '@shared/session';
import { fmtTime, useFavorites, useProgressMap } from '@shared/userdata';
import { rating10, type MediaRef } from '@shared/xtream';
import { requireAccount } from '@/components/RequireAccount';
import { Button, ErrorBox, Loading, s } from '@/components/ui';
import { DownloadButton } from '@/components/DownloadButton';
import { play } from '@/lib/nav';
import { C, R } from '@/theme';

export default requireAccount(Movie);

function Movie() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const nav = useNavigation();
  const { account } = useSession();
  const acc = account!.id;
  const info = useVodInfo(id);
  const list = useVodStreams();
  const item = useMemo(() => list.data?.find((m) => String(m.stream_id) === id), [list.data, id]);
  const { isFav, toggle } = useFavorites(acc);
  const progress = useProgressMap(acc)[`movie:${id}`];

  const i = info.data?.info ?? {};
  const name = info.data?.movie_data?.name || item?.name || i.name || 'Movie';
  const poster = i.movie_image || i.cover_big || item?.stream_icon;
  const backdrop = i.backdrop_path?.[0];
  const ref: MediaRef = {
    kind: 'movie',
    id,
    name,
    poster,
    ext: info.data?.movie_data?.container_extension || item?.container_extension,
  };
  useEffect(() => nav.setOptions({ title: name }), [nav, name]);

  if (info.loading && !info.data && !item) return <Loading />;
  const r = rating10({ ...item, ...i });
  const fav = isFav('movie', id);
  const resume = progress && !progress.watched && progress.pos > 20 ? progress.pos : null;

  return (
    <ScrollView contentContainerStyle={{ paddingBottom: 32 }}>
      {backdrop ? <Image source={{ uri: backdrop }} style={{ width: '100%', aspectRatio: 16 / 9 }} contentFit="cover" /> : null}
      <View style={[s.pad]}>
        <View style={[s.row, { alignItems: 'flex-start', gap: 14 }]}>
          {poster ? <Image source={{ uri: poster }} style={{ width: 110, height: 165, borderRadius: R, backgroundColor: C.bg3 }} contentFit="cover" /> : null}
          <View style={{ flex: 1, gap: 6 }}>
            <Text style={s.h1}>{name}</Text>
            <Text style={s.muted}>
              {[i.releasedate || i.release_date, i.duration, i.genre, r ? `★ ${r}` : null].filter(Boolean).join(' · ')}
            </Text>
          </View>
        </View>
        <View style={{ gap: 10 }}>
          <Button kind="primary" title={resume ? `▶ Resume from ${fmtTime(resume)}` : '▶ Play'} onPress={() => play(ref, resume ? { from: resume } : {})} />
          {resume ? <Button title="Play from the start" onPress={() => play(ref, { from: 0 })} /> : null}
          <View style={[s.row]}>
            <Button style={{ flex: 1 }} title={fav ? '★ Favorite' : '☆ Favorite'} onPress={() => toggle({ kind: 'movie', id, name, poster })} />
            <View style={{ flex: 1 }}>
              <DownloadButton refItem={ref} />
            </View>
          </View>
        </View>
        {info.error && !info.data ? <ErrorBox error={info.error} onRetry={info.refresh} /> : null}
        {i.plot || i.description ? <Text style={s.text}>{i.plot || i.description}</Text> : null}
        {i.cast || i.actors ? <Text style={s.muted}>Cast: {i.cast || i.actors}</Text> : null}
        {i.director ? <Text style={s.muted}>Director: {i.director}</Text> : null}
      </View>
    </ScrollView>
  );
}
