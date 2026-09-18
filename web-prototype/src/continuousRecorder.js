// Continuous recording: a "just film the whole session" alternative to
// gesturing/pressing save for every single arrow. Added on user feedback
// after trying another delay-cam app - a fixed start/stop gesture (or
// button) that records straight through until stopped, saved as one clip.
//
// Unlike clipRecorder.js's backtrack export (which assembles a clip out of
// already-buffered past frames via canvas redraws), this records forward
// from "now", so it can record directly off the raw camera MediaStream with
// MediaRecorder - no canvas involved, full quality, minimal CPU overhead.
// It runs independently of the rolling delay buffer and the per-shot
// save/replay flow; both can be used at the same time.

import { pickMimeType } from './clipRecorder.js';

export class ContinuousRecorder {
  constructor(stream) {
    this.stream = stream;
    this._recorder = null;
    this._chunks = [];
    this._startedAt = 0;
  }

  get isRecording() {
    return !!this._recorder && this._recorder.state === 'recording';
  }

  get elapsedMs() {
    return this.isRecording ? Date.now() - this._startedAt : 0;
  }

  start() {
    if (this.isRecording) return;
    const mimeType = pickMimeType();
    if (!window.MediaRecorder || !mimeType) {
      throw new Error('MediaRecorder not supported in this browser');
    }
    this._chunks = [];
    this._recorder = new MediaRecorder(this.stream, { mimeType, videoBitsPerSecond: 4_000_000 });
    this._recorder.ondataavailable = (e) => {
      if (e.data && e.data.size) this._chunks.push(e.data);
    };
    this._recorder.start(1000); // 1s timeslices so a crash/tab-kill loses at most ~1s
    this._startedAt = Date.now();
  }

  /** Resolves with the recorded Blob once the recorder has fully flushed. */
  stop() {
    return new Promise((resolve, reject) => {
      const recorder = this._recorder;
      if (!recorder || recorder.state === 'inactive') {
        reject(new Error('not recording'));
        return;
      }
      const mimeType = recorder.mimeType;
      recorder.onstop = () => resolve(new Blob(this._chunks, { type: mimeType }));
      recorder.onerror = (e) => reject(e.error || new Error('recorder error'));
      recorder.stop();
      this._recorder = null;
    });
  }
}
