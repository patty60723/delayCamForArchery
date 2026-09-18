// Draws the "N seconds ago" buffered frame onto the live canvas, plus the
// reference guide lines (spec 2.1 / 3.1). Gesture detection now scans the
// whole frame (see gestureEngine.js) rather than a fixed corner ROI, so there
// is no ROI box to draw here anymore.

import { drawCover } from './utils.js';

export class DelayRenderer {
  constructor(canvasEl, frameBuffer, getSettings) {
    this.canvas = canvasEl;
    this.ctx = canvasEl.getContext('2d');
    this.frameBuffer = frameBuffer;
    this.getSettings = getSettings;
    this._running = false;
    this._rafId = null;
    this.onWarmupChange = null; // (remainingSec|null) => void
    this._lastWarmupState = undefined;
  }

  start() {
    if (this._running) return;
    this._running = true;
    const tick = () => {
      if (!this._running) return;
      this._draw();
      this._rafId = requestAnimationFrame(tick);
    };
    this._rafId = requestAnimationFrame(tick);
  }

  stop() {
    this._running = false;
    if (this._rafId) cancelAnimationFrame(this._rafId);
    this._rafId = null;
  }

  _resizeToDisplaySize() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const w = Math.round(rect.width * dpr);
    const h = Math.round(rect.height * dpr);
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
  }

  _draw() {
    this._resizeToDisplaySize();
    const settings = this.getSettings();
    const ctx = this.ctx;
    const W = this.canvas.width, H = this.canvas.height;
    const now = performance.now();
    const targetTs = now - settings.delaySec * 1000;
    const frame = this.frameBuffer.getFrameAt(targetTs);

    const oldest = this.frameBuffer.oldestTs;
    if (oldest === null || frame === null) {
      const warmedSince = oldest === null ? 0 : now - oldest;
      const remaining = Math.max(0, Math.ceil(settings.delaySec - warmedSince / 1000));
      this._setWarmup(remaining);
      ctx.fillStyle = '#05070a';
      ctx.fillRect(0, 0, W, H);
      return;
    }
    this._setWarmup(null);

    drawCover(ctx, frame.canvas, frame.canvas.width, frame.canvas.height, W, H);
    this._drawGuides(ctx, W, H, settings);
  }

  _setWarmup(remaining) {
    if (remaining === this._lastWarmupState) return;
    this._lastWarmupState = remaining;
    if (this.onWarmupChange) this.onWarmupChange(remaining);
  }

  _drawGuides(ctx, W, H, settings) {
    ctx.save();
    ctx.strokeStyle = 'rgba(255,255,255,0.55)';
    ctx.lineWidth = 1.5;
    if (settings.guideV) {
      ctx.beginPath();
      ctx.moveTo(W / 2, 0);
      ctx.lineTo(W / 2, H);
      ctx.stroke();
    }
    if (settings.guideH) {
      ctx.beginPath();
      ctx.moveTo(0, H / 2);
      ctx.lineTo(W, H / 2);
      ctx.stroke();
    }
    ctx.restore();
  }
}
