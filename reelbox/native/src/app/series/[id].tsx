import { useEffect, useMemo, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { useLocalSearchParams, useNavigation } from 'expo-router';
import { useSeriesInfo, useSeriesList } from '@shared/catalog';
import { useSession } from '@shared/session';
import { continueWatching, fmtTime, useFavorites, useProgressMap } from '@shared/userdata';
import { episodeRef, rating10 } from '@shared/xtream';
import { requireAccount } from '@/components/RequireAccount';
import { Button, Chip, ErrorBox, Loading, s } from '@/components/ui';
import { DownloadButton } from '@/components/DownloadButton';
import { play } from '@/lib/nav';
import { C, R } from '@/theme';

export default requireAccount(Series);

function Series() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const nav = useNavigation();
  const { account } = useSession();
  const acc = account!.id;
  const info = useSeriesInfo(id);
  const list = useSeriesList();
  const item = useMemo(() => list.data?.find((m) => String(m.series_id) === id), [list.data, id]);
  const { isFav, toggle } = useFavorites(acc);
  const progress = useProgressMap(acc);
  const name = info.data?.info?.name || item?.name || 'Series';
  const cover = info.data?.info?.cover || item?.cover;
  useEffect(() => nav.setOptions({ title: name }), [nav, name]);

  const seasons = info.data?.seasons ?? [];
  const resumeEntry = useMemo(
    () => continueWatching(progress).find((p) => p.ref.seriesId === id),
    [progress, id],
  );
  const [season, setSeason] = useState<number | null>(null);
  const current = season ?? resumeEntry?.ref.season ?? seasons[0]?.season_number ?? 1;
  const eps = info.data?.episodes[String(current)] ?? [];
  const series = { id, name, cover };

  if (info.loading && !info.data) return <Loading />;
  const fav = isFav('series', id);
  const r = rating10(info.data?.info ?? item ?? {});

  // First unwatched episode, for "Play" when nothing is in progress.
  const firstUnwatched = () => {
    for (const s_ of seasons) for (const e of info.data!.episodes[String(s_.season_number)] ?? []) if (!progress[`episode:${e.id}`]?.watched) return episodeRef(e, series);
    return null;
  };

  return (
    <ScrollView contentContainerStyle={{ paddingBottom: 32 }}>
      <View style={s.pad}>
        <View style={[s.row, { alignItems: 'flex-start', gap: 14 }]}>
          {cover ? <Image source={{ uri: cover }} style={{ width: 110, height: 165, borderRadius: R, backgroundColor: C.bg3 }} contentFit="cover" /> : null}
          <View style={{ flex: 1, gap: 6 }}>
            <Text style={s.h1}>{name}</Text>
            <Text style={s.muted}>
              {[info.data?.info?.releaseDate || info.data?.info?.release_date, info.data?.info?.genre, r ? `★ ${r}` : null].filter(Boolean).join(' · ')}
            </Text>
          </View>
        </View>
        <View style={{ gap: 10 }}>
          {resumeEntry ? (
            <Button
              kind="primary"
              title={`▶ Resume S${resumeEntry.ref.season}E${resumeEntry.ref.episode} · ${fmtTime(resumeEntry.pos)}`}
              onPress={() => play(resumeEntry.ref, { from: resumeEntry.pos })}
            />
          ) : (
            <Button
              kind="primary"
              title="▶ Play"
              onPress={() => {
                const r_ = firstUnwatched();
                if (r_) play(r_);
              }}
            />
          )}
          <Button title={fav ? '★ Favorite' : '☆ Favorite'} onPress={() => toggle({ kind: 'series', id, name, poster: cover })} />
        </View>
        {info.error && !info.data ? <ErrorBox error={info.error} onRetry={info.refresh} /> : null}
        {info.data?.info?.plot ? <Text style={s.text}>{info.data.info.plot}</Text> : null}
        {seasons.length > 1 ? (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
            {seasons.map((se) => (
              <Chip key={se.season_number} label={se.name || `Season ${se.season_number}`} active={se.season_number === current} onPress={() => setSeason(se.season_number)} />
            ))}
          </ScrollView>
        ) : null}
        <View style={{ gap: 8 }}>
          {eps.map((e) => {
            const ref = episodeRef(e, series);
            const p = progress[`episode:${e.id}`];
            return (
              <View key={e.id} style={[s.card, { gap: 10 }]}>
                <Pressable onPress={() => play(ref, p && !p.watched ? { from: p.pos } : {})} accessibilityRole="button" style={[s.row, { alignItems: 'flex-start' }]}>
                  <View style={{ width: 120, aspectRatio: 16 / 9, borderRadius: 8, overflow: 'hidden', backgroundColor: C.bg3 }}>
                    {e.info?.movie_image ? <Image source={{ uri: e.info.movie_image }} style={{ flex: 1 }} contentFit="cover" /> : null}
                    {p ? (
                      <View style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 4, backgroundColor: '#0008' }}>
                        <View style={{ height: 4, width: `${p.watched ? 100 : (p.pos / p.dur) * 100}%`, backgroundColor: C.accent }} />
                      </View>
                    ) : null}
                  </View>
                  <View style={{ flex: 1, gap: 3 }}>
                    <Text style={s.h3} numberOfLines={2}>
                      {e.episode_num}. {e.title}
                    </Text>
                    <Text style={s.muted}>
                      {[e.info?.duration, p?.watched ? 'Watched' : p ? `${fmtTime(p.dur - p.pos)} left` : null].filter(Boolean).join(' · ')}
                    </Text>
                  </View>
                </Pressable>
                {e.info?.plot ? (
                  <Text style={s.muted} numberOfLines={3}>
                    {e.info.plot}
                  </Text>
                ) : null}
                <DownloadButton small refItem={ref} />
              </View>
            );
          })}
        </View>
      </View>
    </ScrollView>
  );
}
