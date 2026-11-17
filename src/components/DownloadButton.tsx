import { downloads, driverCanPause, fmtBytes, pickDriver, useDownload } from '../lib/downloads';
import { refKey, type MediaRef, type Xtream } from '../lib/xtream';
import { Icon, toast } from './ui';

export function DownloadButton({ client, ref_, compact }: { client: Xtream; ref_: MediaRef; compact?: boolean }) {
  const key = refKey(ref_);
  const d = useDownload(key);
  if (ref_.kind === 'live' || !pickDriver()) return null;
  const pct = d && d.total ? Math.floor((d.bytes / d.total) * 100) : 0;

  const label = !d
    ? 'Download'
    : d.status === 'done'
      ? 'Downloaded'
      : d.status === 'downloading'
        ? d.total
          ? `${pct}%`
          : fmtBytes(d.bytes)
        : d.status === 'queued'
          ? 'Queued'
          : d.status === 'paused'
            ? `Paused ${pct}%`
            : 'Retry download';

  const onClick = async () => {
    try {
      if (!d || d.status === 'error') {
        await downloads.enqueue(client, ref_);
        toast(`Downloading “${ref_.name}” for offline`);
      } else if (d.status === 'downloading' && driverCanPause(d.driver)) downloads.pause(key);
      else if (d.status === 'paused') downloads.resume(key);
      else if (d.status === 'done') toast('Already available offline — manage it in Downloads');
    } catch (e) {
      toast((e as Error).message);
    }
  };

  return (
    <button
      className={`btn ${compact ? 'btn-icon' : ''} ${d?.status === 'done' ? 'on' : ''} dl-${d?.status ?? 'none'}`}
      onClick={onClick}
      title={d?.error ?? label}
      aria-label={label}
      style={d?.status === 'downloading' && d.total ? ({ '--pct': `${pct}%` } as React.CSSProperties) : undefined}
    >
      <Icon name={d?.status === 'done' ? 'check' : d?.status === 'downloading' ? 'pause' : 'download'} size={compact ? 18 : 22} />
      {!compact && label}
    </button>
  );
}
