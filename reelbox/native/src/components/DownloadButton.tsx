import { useEffect, useState } from 'react';
import { Alert } from 'react-native';
import { useClient } from '@shared/session';
import { refKey, type MediaRef } from '@shared/xtream';
import { liveProgress, pauseDownload, removeDownload, resumeDownload, startDownload, subscribeProgress, useDownloads } from '@/lib/downloads';
import { Button } from './ui';
import { toast } from './ui';

export function DownloadButton({ refItem, small }: { refItem: MediaRef; small?: boolean }) {
  const client = useClient();
  const map = useDownloads();
  const key = refKey(refItem);
  const d = map[key];
  const [, tick] = useState(0);
  useEffect(() => subscribeProgress(() => tick((n) => n + 1)), []);
  if (!d)
    return (
      <Button
        small={small}
        title="⤓ Download"
        onPress={() => {
          startDownload(client, refItem);
          toast('Downloading — see Library › Downloads');
        }}
      />
    );
  const lp = liveProgress(key);
  const total = lp?.total || d.total;
  const pct = total ? Math.floor(((lp?.bytes ?? d.bytes) / total) * 100) : null;
  switch (d.status) {
    case 'done':
      return (
        <Button
          small={small}
          kind="ghost"
          title="✓ Saved offline"
          onPress={() =>
            Alert.alert('Saved on this phone', 'Delete the download?', [
              { text: 'Keep', style: 'cancel' },
              { text: 'Delete', style: 'destructive', onPress: () => removeDownload(key) },
            ])
          }
        />
      );
    case 'downloading':
    case 'queued':
      return <Button small={small} title={pct != null ? `Pause ${pct}%` : 'Pause download'} onPress={() => pauseDownload(key)} />;
    default:
      return <Button small={small} title={d.status === 'error' ? 'Retry download' : `Resume ${pct ?? 0}%`} onPress={() => resumeDownload(key)} />;
  }
}
