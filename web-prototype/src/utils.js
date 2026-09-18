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

