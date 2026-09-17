// Draws the "N seconds ago" buffered frame onto the live canvas, plus the
// reference guide lines and the gesture ROI box (spec 2.1 / 3.1).

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

    ctx.drawImage(frame.canvas, 0, 0, W, H);
    this._drawGuides(ctx, W, H, settings);
    this._drawRoiBox(ctx, W, H, settings);
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

  _drawRoiBox(ctx, W, H, settings) {
    const box = roiRect(W, H, settings.dominantHand);
    ctx.save();
    ctx.strokeStyle = 'rgba(255,122,26,0.8)';
    ctx.lineWidth = 2;
    ctx.setLineDash([6, 6]);
    ctx.strokeRect(box.x, box.y, box.w, box.h);
    ctx.restore();
  }
}

// Gesture ROI: a fixed corner box, bottom-left or bottom-right depending on
// dominant hand (spec 3.3 / 4).
export function roiRect(W, H, dominantHand) {
  const w = W * 0.32;
  const h = H * 0.28;
  const margin = W * 0.04;
  const x = dominantHand === 'left' ? margin : W - margin - w;
  const y = H - margin - h;
  return { x, y, w, h };
}
