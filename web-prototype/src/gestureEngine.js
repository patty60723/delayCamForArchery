// Gesture triggers (spec 2.2 / 5.3 / 9.1): watches the WHOLE frame of the
// LIVE (undelayed) camera feed for two independent hand gestures, each
// requiring ~0.3-0.5s of stable detection before firing, per the 9.1 review
// note that a single-frame hit is too easy to false-trigger while handling
// equipment. (An earlier version restricted detection to a fixed corner ROI
// per spec 3.3/5.3, but that made a gesture awkward to land reliably in
// practice - full-frame detection is more forgiving about hand position.)
//
//  - Open_Palm  -> per-shot backtrack save (spec 2.2), same as before.
//  - Thumb_Up   -> start/stop continuous recording (see continuousRecorder.js).
//    Added on user feedback: doing the save gesture for every single arrow
//    got tedious, so this gives a "just film the whole session" option that
//    only needs two gestures total (start, stop) instead of one per shot.
//
// Both are MediaPipe GestureRecognizer canned categories, picked as the
// closest built-in match to a natural, easy-to-repeat hand shape - MediaPipe
// ships no bespoke gestures, so this reuses its fixed vocabulary rather than
// training a custom one.
//
// The two gestures are mutually exclusive per frame (recognizeForVideo only
// returns one top category), so evaluating both against the same `top`
// result is enough - no risk of one frame satisfying both at once. The
// save gesture is suppressed while `locked` (SAVING/REPLAY, so a stray
// detection can't re-trigger mid-replay); the record toggle is deliberately
// NOT gated by `locked` - starting/stopping the continuous recording is
// independent of the per-shot review flow and should keep working while a
// replay is on screen.
//
// The WASM runtime + model are loaded from Google's public CDN at runtime
// (standard MediaPipe web usage); if that fetch fails - offline, or a
// network policy blocks it - gesture recognition simply stays unavailable
// and the manual buttons remain the fallback, exactly as spec 3.1 describes
// for "手勢辨識失敗時的備援".

const VISION_BUNDLE_URL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/vision_bundle.mjs';
const WASM_BASE_URL = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm';
const MODEL_URL = 'https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task';

const DETECT_INTERVAL_MS = 110; // ~9 checks/sec, plenty for a 0.3-0.5s stability window
const STABLE_MS = 350;
const MIN_SCORE = 0.6;
const COOLDOWN_MS = 3000; // spec 5.3: cool-down so one gesture isn't counted twice

const SAVE_CATEGORY = 'Open_Palm';
const RECORD_TOGGLE_CATEGORY = 'Thumb_Up';

function freshStreak() {
  return { streakStart: 0, lastFireAt: 0 };
}

export class GestureEngine {
  constructor({ onGesture, onToggleRecord, onStatusChange }) {
    this.onGesture = onGesture;
    this.onToggleRecord = onToggleRecord;
    this.onStatusChange = onStatusChange;
    this.available = false;
    this.enabled = false; // whether the detection loop should currently run
    this._recognizer = null;
    this._video = null;
    this._timer = null;
    this._save = freshStreak();
    this._recordToggle = freshStreak();
    this._locked = false; // external lock, e.g. while saving/replaying - save gesture only
  }

  async init(videoEl) {
    this._video = videoEl;
    this._status('手勢辨識初始化中…');
    try {
      const { FilesetResolver, GestureRecognizer } = await import(/* webpackIgnore: true */ VISION_BUNDLE_URL);
      const files = await FilesetResolver.forVisionTasks(WASM_BASE_URL);
      const options = {
        baseOptions: { modelAssetPath: MODEL_URL, delegate: 'GPU' },
        runningMode: 'VIDEO',
        numHands: 1,
      };
      // GPU delegate support is inconsistent across real mobile GPU/Chrome
      // combinations - fall back to CPU rather than losing gesture
      // recognition entirely over a delegate mismatch.
      try {
        this._recognizer = await GestureRecognizer.createFromOptions(files, options);
      } catch (gpuErr) {
        console.warn('[gestureEngine] GPU delegate failed, retrying on CPU:', gpuErr);
        options.baseOptions.delegate = 'CPU';
        this._recognizer = await GestureRecognizer.createFromOptions(files, options);
      }
      this.available = true;
      this._status('手勢辨識: 已啟用', 'ok');
    } catch (err) {
      console.warn('[gestureEngine] unavailable, falling back to manual button only:', err);
      this.available = false;
      this._status('手勢辨識未啟用(用手動按鈕)', 'muted');
    }
  }

  _status(text, tone = 'muted') {
    if (this.onStatusChange) this.onStatusChange(text, tone);
  }

  setLocked(locked) {
    this._locked = locked;
    if (locked) this._save.streakStart = 0;
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
    this._save.streakStart = 0;
    this._recordToggle.streakStart = 0;
  }

  _tick() {
    if (!this._recognizer || !this._video || this._video.readyState < 2) return;

    let result;
    try {
      result = this._recognizer.recognizeForVideo(this._video, performance.now());
    } catch (err) {
      return; // transient decode hiccup, just skip this tick
    }

    const top = result?.gestures?.[0]?.[0];
    const now = performance.now();

    if (!this._locked) {
      const isSaveGesture = top && top.categoryName === SAVE_CATEGORY && top.score >= MIN_SCORE;
      this._evaluate(this._save, isSaveGesture, now, this.onGesture);
    }

    const isRecordToggleGesture = top && top.categoryName === RECORD_TOGGLE_CATEGORY && top.score >= MIN_SCORE;
    this._evaluate(this._recordToggle, isRecordToggleGesture, now, this.onToggleRecord);
  }

  _evaluate(state, matched, now, onFire) {
    if (!matched) {
      state.streakStart = 0;
      return;
    }
    if (state.streakStart === 0) state.streakStart = now;
    const heldFor = now - state.streakStart;
    if (heldFor >= STABLE_MS && now - state.lastFireAt >= COOLDOWN_MS) {
      state.lastFireAt = now;
      state.streakStart = 0;
      onFire && onFire();
    }
  }
}
