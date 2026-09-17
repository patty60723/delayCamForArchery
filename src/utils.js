export function pad2(n) {
  return String(n).padStart(2, '0');
}

// Matches spec 6: archery_yyyyMMdd_HHmmss.mp4 (webm in this web prototype, see README)
export function clipFilename(date = new Date()) {
  const y = date.getFullYear();
  const m = pad2(date.getMonth() + 1);
  const d = pad2(date.getDate());
  const hh = pad2(date.getHours());
  const mm = pad2(date.getMinutes());
  const ss = pad2(date.getSeconds());
  return `archery_${y}${m}${d}_${hh}${mm}${ss}.webm`;
}

export function formatDuration(ms) {
  const s = Math.round(ms / 100) / 10;
  return `${s}s`;
}

export function formatClock(ts) {
  const d = new Date(ts);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

