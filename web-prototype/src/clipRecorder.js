// Turns a slice of buffered frames (spec 2.2 step 1: 回溯存檔) into:
//  - an instant on-screen slow-motion replay (playFrames), so the app can
//    flip to the replay screen the moment a gesture/button fires, and
//  - a persisted video file for the gallery (encodeClip), built in the
//    background via canvas.captureStream()+MediaRecorder so the saved file
//    reflects the true real-time footage (slow-mo is then just a playback
//    rate applied when viewing it, in the live replay and again in Gallery).

import { drawCover } from './utils.js';

function pickMimeType() {
  const candidates = [
    'video/webm;codecs=vp9',
    'video/webm;codecs=vp8',
    'video/webm',
  ];
  for (const type of candidates) {
    if (window.MediaRecorder && MediaRecorder.isTypeSupported(type)) return type;
  }
  return '';
}

/**
 * Replays buffered frames onto `canvas`, spaced out by `1/rate` of their
 * original timing, so rate=0.5 plays at half speed. Resolves once finished,
 * or immediately if `cancel.requested` becomes true between frames.
 */
export function playFrames(canvas, frames, rate, { onProgress, cancel } = {}) {
  const ctx = canvas.getContext('2d');
  return new Promise((resolve) => {
    if (!frames.length) return resolve();
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = Math.round(rect.width * dpr) || frames[0].canvas.width;
    canvas.height = Math.round(rect.height * dpr) || frames[0].canvas.height;

    const totalSpan = frames[frames.length - 1].ts - frames[0].ts || 1;
    let i = 0;

    const drawNext = () => {
      if (cancel && cancel.requested) return resolve();
      if (i >= frames.length) return resolve();
      const f = frames[i];
      drawCover(ctx, f.canvas, f.canvas.width, f.canvas.height, canvas.width, canvas.height);
      if (onProgress) onProgress((f.ts - frames[0].ts) / totalSpan);
      const next = frames[i + 1];
      i += 1;
      if (!next) {
        // hold the last frame briefly then finish
        setTimeout(resolve, 150 / rate);
        return;
      }
      const delta = (next.ts - f.ts) / rate;
      setTimeout(drawNext, Math.max(1, delta));
    };
    drawNext();
  });
}

/**
 * Encodes buffered frames into a real-time-duration webm Blob for gallery
 * storage. Runs in the background - callers should not block the UI on it.
 */
export function encodeClip(frames, { width = 480, height = 270 } = {}) {
  return new Promise((resolve, reject) => {
    if (!frames.length) return reject(new Error('no frames to encode'));
    const mimeType = pickMimeType();
    if (!window.MediaRecorder || !mimeType) {
      return reject(new Error('MediaRecorder not supported in this browser'));
    }

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    drawCover(ctx, frames[0].canvas, frames[0].canvas.width, frames[0].canvas.height, width, height);

    const stream = canvas.captureStream(15);
    const recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 1_500_000 });
    const chunks = [];
    recorder.ondataavailable = (e) => {
      if (e.data && e.data.size) chunks.push(e.data);
    };
    recorder.onerror = (e) => reject(e.error || new Error('recorder error'));
    recorder.onstop = () => resolve(new Blob(chunks, { type: mimeType }));

    recorder.start();

    let i = 0;
    const step = () => {
      if (i >= frames.length) {
        recorder.stop();
        return;
      }
      const f = frames[i];
      drawCover(ctx, f.canvas, f.canvas.width, f.canvas.height, width, height);
      const next = frames[i + 1];
      i += 1;
      if (!next) {
        setTimeout(() => recorder.stop(), 120);
        return;
      }
      setTimeout(step, Math.max(1, next.ts - f.ts));
    };
    step();
  });
}
