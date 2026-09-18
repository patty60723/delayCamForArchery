// Rolling buffer (spec 2.1 / 5.2): continuously samples the live camera
// feed into an in-memory ring of small canvases tagged with a timestamp.
// The delay preview and the "回溯存檔" clip both read from this buffer.
//
// The real Android spec records 60fps segment files and stitches them for
// export; this browser prototype samples at a lower fixed rate (good enough
// to demonstrate the delay-mirror + backtrack-save behaviour) and keeps
// everything in memory as canvases instead of encoded segments.

const SAMPLE_INTERVAL_MS = 1000 / 15; // ~15fps sampling
const MARGIN_SEC = 2; // keep a little extra history beyond what's needed

export class FrameBuffer {
  constructor({ width = 384, height = 216 } = {}) {
    this.width = width;
    this.height = height;
    this._frames = []; // { ts, canvas } ascending by ts
    this._maxAgeMs = 10000;
    this._sourceEl = null;
    this._running = false;
    this._lastSampleTs = 0;
    this._rafId = null;
  }

  attachSource(videoEl) {
    this._sourceEl = videoEl;
  }

  setMaxAgeMs(ms) {
    this._maxAgeMs = ms;
  }

  start() {
    if (this._running) return;
    this._running = true;
    const tick = (now) => {
      if (!this._running) return;
      if (now - this._lastSampleTs >= SAMPLE_INTERVAL_MS) {
        this._lastSampleTs = now;
        this._sample();
      }
      this._rafId = requestAnimationFrame(tick);
    };
    this._rafId = requestAnimationFrame(tick);
  }

  stop() {
    this._running = false;
    if (this._rafId) cancelAnimationFrame(this._rafId);
    this._rafId = null;
  }

  clear() {
    this._frames = [];
  }

  _sample() {
    const video = this._sourceEl;
    if (!video || video.readyState < 2 || !video.videoWidth) return;
    const canvas = document.createElement('canvas');
    canvas.width = this.width;
    canvas.height = this.height;
    const ctx = canvas.getContext('2d');
    // cover-fit crop of the source video into the buffer canvas
    const srcRatio = video.videoWidth / video.videoHeight;
    const dstRatio = this.width / this.height;
    let sx = 0, sy = 0, sw = video.videoWidth, sh = video.videoHeight;
    if (srcRatio > dstRatio) {
      sw = video.videoHeight * dstRatio;
      sx = (video.videoWidth - sw) / 2;
    } else {
      sh = video.videoWidth / dstRatio;
      sy = (video.videoHeight - sh) / 2;
    }
    ctx.drawImage(video, sx, sy, sw, sh, 0, 0, this.width, this.height);
    const ts = performance.now();
    this._frames.push({ ts, canvas });
    this._trim(ts);
  }

  _trim(now) {
    const cutoff = now - this._maxAgeMs;
    let i = 0;
    while (i < this._frames.length && this._frames[i].ts < cutoff) i++;
    if (i > 0) this._frames.splice(0, i);
  }

  get oldestTs() {
    return this._frames.length ? this._frames[0].ts : null;
  }

  get newestTs() {
    return this._frames.length ? this._frames[this._frames.length - 1].ts : null;
  }

  /** Nearest buffered frame at or before targetTs, or null if history doesn't reach that far back. */
  getFrameAt(targetTs) {
    const frames = this._frames;
    if (!frames.length) return null;
    if (frames[0].ts > targetTs) return null; // buffer hasn't warmed up that far yet
    let lo = 0, hi = frames.length - 1, ans = frames[0];
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (frames[mid].ts <= targetTs) {
        ans = frames[mid];
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return ans;
  }

  getFramesInRange(fromTs, toTs) {
    return this._frames.filter((f) => f.ts >= fromTs && f.ts <= toTs);
  }
}

export { MARGIN_SEC };
