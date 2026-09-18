export function pad2(n) {
  return String(n).padStart(2, '0');
}

function timestamp(date) {
  const y = date.getFullYear();
  const m = pad2(date.getMonth() + 1);
  const d = pad2(date.getDate());
  const hh = pad2(date.getHours());
  const mm = pad2(date.getMinutes());
  const ss = pad2(date.getSeconds());
  return `${y}${m}${d}_${hh}${mm}${ss}`;
}

// Matches spec 6: archery_yyyyMMdd_HHmmss.mp4 (webm in this web prototype, see README)
export function clipFilename(date = new Date()) {
  return `archery_${timestamp(date)}.webm`;
}

// Continuous-recording sessions (see continuousRecorder.js) get their own
// prefix so they're visually distinct from per-shot clips in the gallery.
export function sessionFilename(date = new Date()) {
  return `archery_session_${timestamp(date)}.webm`;
}

// Per-shot clips are a few seconds (show "4.2s"); continuous-recording
// sessions can run many minutes, where that format stops being readable, so
// switch to mm:ss past one minute.
export function formatDuration(ms) {
  if (ms >= 60_000) return formatElapsed(ms);
  const s = Math.round(ms / 100) / 10;
  return `${s}s`;
}

// mm:ss for the live REC timer badge (and long clip durations above).
export function formatElapsed(ms) {
  const totalSec = Math.floor(ms / 1000);
  const mm = pad2(Math.floor(totalSec / 60));
  const ss = pad2(totalSec % 60);
  return `${mm}:${ss}`;
}

export function formatClock(ts) {
  const d = new Date(ts);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

// Draws `source` (image/video/canvas) into the ctx's destW x destH area with
// object-fit: cover semantics - crop to the destination's aspect ratio
// instead of stretching, so a 16:9 buffered frame doesn't get squashed into
// a tall portrait phone screen (or vice versa).
export function drawCover(ctx, source, srcW, srcH, destW, destH) {
  if (!srcW || !srcH || !destW || !destH) return;
  const srcRatio = srcW / srcH;
  const destRatio = destW / destH;
  let sx = 0, sy = 0, sw = srcW, sh = srcH;
  if (srcRatio > destRatio) {
    sw = srcH * destRatio;
    sx = (srcW - sw) / 2;
  } else {
    sh = srcW / destRatio;
    sy = (srcH - sh) / 2;
  }
  ctx.drawImage(source, sx, sy, sw, sh, 0, 0, destW, destH);
}

