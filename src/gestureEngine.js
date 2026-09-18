// Gesture trigger (spec 2.2 / 5.3 / 9.1): watches the WHOLE frame of the
// LIVE (undelayed) camera feed for an open-hand gesture and fires onGesture()
// once it has been stable for ~0.3-0.5s, per the 9.1 review note that a
// single-frame hit is too easy to false-trigger while handling equipment.
// (An earlier version restricted detection to a fixed corner ROI per spec
// 3.3/5.3, but that made the gesture awkward to land reliably in practice -
// full-frame detection is more forgiving about hand position.)
//
// Uses MediaPipe's GestureRecognizer (Open_Palm category) as a robust stand-in
// for "五指張開" (open/spread hand) - MediaPipe does not ship a bespoke
// "five" digit-counting gesture, and Open_Palm is the closest built-in match.
// The WASM runtime + model are loaded from Google's public CDN at runtime
// (standard MediaPipe web usage); if that fetch fails - offline, or a
// network policy blocks it - gesture recognition simply stays unavailable
// and the manual save button remains the fallback, exactly as spec 3.1
// describes for "手勢辨識失敗時的備援".

const VISION_BUNDLE_URL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/vision_bundle.mjs';
const WASM_BASE_URL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm';
const MODEL_URL = 'https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task';

const DETECT_INTERVAL_MS = 110; // ~9 checks/sec, plenty for a 0.3-0.5s stability window
const STABLE_MS = 350;
const MIN_SCORE = 0.6;
const COOLDOWN_MS = 3000; // spec 5.3: cool-down so one gesture isn't counted twice

export class GestureEngine {
  constructor({ onGesture, onStatusChange }) {
    this.onGesture = onGesture;
    this.onStatusChange = onStatusChange;
    this.available = false;
    this.enabled = false; // whether the detection loop should currently run
    this._recognizer = null;
    this._video = null;
    this._timer = null;
    this._streakStart = 0;
    this._lastFireAt = 0;
    this._locked = false; // external lock, e.g. while saving/replaying
  }

  async init(videoEl) {
    this._video = videoEl;
    this._status('手勢辨識初始化中…');
    try {
      const { FilesetResolver, GestureRecognizer } = await import(/* webpackIgnore: true */ VISION_BUNDLE_URL);
      const files = await FilesetResolver.forVisionTasks(WASM_BASE_URL);
      this._recognizer = await GestureRecognizer.createFromOptions(files, {
        baseOptions: { modelAssetPath: MODEL_URL, delegate: 'GPU' },
        runningMode: 'VIDEO',
        numHands: 1,
      });
      this.available = true;
      this._status('手勢辨識: 已啟用', 'ok');
    } catch (err) {
      console.warn('[gestureEngine] unavailable, falling back to manual button only:', err);
      this.available = false;
      this._status('手勢辨識未啟用,請用手動存檔按鈕', 'muted');
    }
  }

  _status(text, tone = 'muted') {
    if (this.onStatusChange) this.onStatusChange(text, tone);
  }

  setLocked(locked) {
    this._locked = locked;
    if (locked) {
      this._streakStart = 0;
    }
  }

  start() {
    if (!this.available || this.enabled) return;
    this.enabled = true;
    this._timer = setInterval(() => this._tick(), DETECT_INTERVAL_MS);
  }

  stop() {
    this.enabled = false;
    if (this._timer) clearInterval(this._timer);
    this._timer = null;
    this._streakStart = 0;
  }

  _tick() {
    if (!this._recognizer || !this._video || this._video.readyState < 2) return;
    if (this._locked) return;

    let result;
    try {
      result = this._recognizer.recognizeForVideo(this._video, performance.now());
    } catch (err) {
      return; // transient decode hiccup, just skip this tick
    }

    const top = result?.gestures?.[0]?.[0];
    const isOpenPalm = top && top.categoryName === 'Open_Palm' && top.score >= MIN_SCORE;
    const now = performance.now();

    if (!isOpenPalm) {
      this._streakStart = 0;
      return;
    }
    if (this._streakStart === 0) this._streakStart = now;

    const heldFor = now - this._streakStart;
    if (heldFor >= STABLE_MS && now - this._lastFireAt >= COOLDOWN_MS) {
      this._lastFireAt = now;
      this._streakStart = 0;
      this.onGesture && this.onGesture();
    }
  }
}
