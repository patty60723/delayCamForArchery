import { Store, DEFAULT_SETTINGS, APP_STATE, SCREEN } from './state.js';
import { FrameBuffer, MARGIN_SEC } from './frameBuffer.js';
import { DelayRenderer } from './delayRenderer.js';
import { GestureEngine } from './gestureEngine.js';
import { playFrames, encodeClip } from './clipRecorder.js';
import { ContinuousRecorder } from './continuousRecorder.js';
import { GalleryStore } from './galleryStore.js';
import { loadSettings, saveSettings } from './settingsStore.js';
import { clipFilename, sessionFilename, formatDuration, formatElapsed, formatClock, drawCover } from './utils.js';

// ---------- element refs ----------
const $ = (sel) => document.querySelector(sel);

const camSource = $('#camSource');
const pipVideo = $('#pipVideo');
const liveCanvas = $('#liveCanvas');
const replayCanvas = $('#replayCanvas');
const warmupEl = $('#warmup');
const warmupCountEl = $('#warmupCount');
const gestureBadge = $('#gestureBadge');
const recBadge = $('#recBadge');
const recElapsedEl = $('#recElapsed');
const btnToggleRecord = $('#btnToggleRecord');
const btnManualSave = $('#btnManualSave');
const btnOpenGallery = $('#btnOpenGallery');
const btnOpenSettings = $('#btnOpenSettings');
const btnReturnNow = $('#btnReturnNow');
const replayProgressFill = $('#replayProgressFill');
const toastEl = $('#toast');

const inputs = {
  delaySec: $('#delaySec'),
  delaySecVal: $('#delaySecVal'),
  backtrackSec: $('#backtrackSec'),
  backtrackSecVal: $('#backtrackSecVal'),
  guideV: $('#guideV'),
  guideH: $('#guideH'),
  slowMoRate: $('#slowMoRate'),
  dominantHand: $('#dominantHand'),
};

const galleryListEl = $('#galleryList');
const galleryEmptyEl = $('#galleryEmpty');
const playerVideo = $('#playerVideo');
const playerTitle = $('#playerTitle');
const playerSlowMo = $('#playerSlowMo');
const btnDownloadClip = $('#btnDownloadClip');
const btnDeleteClip = $('#btnDeleteClip');

// ---------- state ----------
const settingsStore = new Store(loadSettings());
const gallery = new GalleryStore();
let appState = APP_STATE.LIVE;
let currentPlayerClipId = null;
const replayCancel = { requested: false };

const frameBuffer = new FrameBuffer({ width: 384, height: 216 });
frameBuffer.attachSource(camSource);

const delayRenderer = new DelayRenderer(liveCanvas, frameBuffer, () => settingsStore.get());
delayRenderer.onWarmupChange = (remaining) => {
  if (remaining === null) {
    warmupEl.classList.add('hidden');
  } else {
    warmupEl.classList.remove('hidden');
    warmupCountEl.textContent = String(remaining);
  }
};

const gestureEngine = new GestureEngine({
  onGesture: () => triggerSave(),
  onToggleRecord: () => toggleContinuousRecording(),
  onStatusChange: (text, tone) => {
    gestureBadge.textContent = text;
    gestureBadge.className = `badge badge--${tone}`;
  },
});

// ---------- continuous recording ----------
// "Just film the whole session" alternative to gesturing/pressing save for
// every single arrow (user feedback after trying another delay-cam app).
// Independent of the LIVE/SAVING/REPLAY state machine below - it keeps
// running through per-shot saves/replays and isn't paused when the tab is
// backgrounded (unlike the delay buffer/gesture loop), since a session
// recording shouldn't silently stop just because the screen dimmed.
let continuousRecorder = null;
let recTimerInterval = null;
let recThumbnail = null;

function updateRecBadge() {
  const recording = !!continuousRecorder && continuousRecorder.isRecording;
  recBadge.classList.toggle('hidden', !recording);
  btnToggleRecord.classList.toggle('icon-btn--active', recording);
  btnToggleRecord.textContent = recording ? '⏹' : '⏺';
  if (recording) recElapsedEl.textContent = formatElapsed(continuousRecorder.elapsedMs);
}

async function toggleContinuousRecording() {
  if (!camSource.srcObject) {
    toast('相機尚未就緒,請稍後再試');
    return;
  }
  if (!continuousRecorder) continuousRecorder = new ContinuousRecorder(camSource.srcObject);

  if (continuousRecorder.isRecording) {
    clearInterval(recTimerInterval);
    recTimerInterval = null;
    const durationMs = continuousRecorder.elapsedMs;
    try {
      const blob = await continuousRecorder.stop();
      const createdAt = Date.now();
      const filename = sessionFilename(new Date(createdAt));
      await gallery.addClip({ filename, createdAt, durationMs, blob, thumbnail: recThumbnail });
      toast(`已存檔:${filename}`);
    } catch (err) {
      console.error('[continuous record] failed', err);
      toast('存檔失敗:儲存空間不足或裝置不支援錄影,請清理空間後再試');
    }
    updateRecBadge();
    return;
  }

  try {
    const thumbCanvas = document.createElement('canvas');
    thumbCanvas.width = 160;
    thumbCanvas.height = 90;
    drawCover(thumbCanvas.getContext('2d'), camSource, camSource.videoWidth, camSource.videoHeight, 160, 90);
    recThumbnail = thumbCanvas.toDataURL('image/jpeg', 0.7);

    continuousRecorder.start();
    recTimerInterval = setInterval(updateRecBadge, 1000);
    updateRecBadge();
    toast('開始連續錄影');
  } catch (err) {
    console.error('[continuous record] start failed', err);
    toast('無法開始錄影:裝置不支援錄影');
  }
}

btnToggleRecord.addEventListener('click', () => toggleContinuousRecording());

// Don't let a page close/refresh silently throw away an in-progress recording.
window.addEventListener('beforeunload', (e) => {
  if (continuousRecorder && continuousRecorder.isRecording) {
    e.preventDefault();
    e.returnValue = '';
  }
});

// ---------- toast ----------
let toastTimer = null;
function toast(msg, ms = 2600) {
  toastEl.textContent = msg;
  toastEl.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.add('hidden'), ms);
}

// ---------- screen navigation ----------
function showScreen(name) {
  document.querySelectorAll('.screen').forEach((el) => {
    el.classList.toggle('screen--active', el.dataset.screen === name);
  });
}

btnOpenGallery.addEventListener('click', () => {
  renderGallery();
  showScreen(SCREEN.GALLERY);
});
btnOpenSettings.addEventListener('click', () => showScreen(SCREEN.SETTINGS));
document.querySelectorAll('[data-back]').forEach((btn) => {
  btn.addEventListener('click', () => showScreen(SCREEN.LIVE));
});
document.querySelectorAll('[data-back-player]').forEach((btn) => {
  btn.addEventListener('click', () => showScreen(SCREEN.GALLERY));
});

// ---------- settings wiring ----------
function applyBufferWindow(settings) {
  const maxAgeMs = (Math.max(settings.delaySec, settings.backtrackSec) + MARGIN_SEC) * 1000;
  frameBuffer.setMaxAgeMs(maxAgeMs);
}

// The live PiP preview sits in the corner opposite the dominant hand, so
// raising that hand to gesture doesn't cover it.
function applyPipPosition(settings) {
  pipVideo.classList.toggle('pip-video--right', settings.dominantHand === 'left');
  pipVideo.classList.toggle('pip-video--left', settings.dominantHand !== 'left');
}

function populateSettingsInputs(settings) {
  inputs.delaySec.value = settings.delaySec;
  inputs.delaySecVal.textContent = `${settings.delaySec} 秒`;
  inputs.backtrackSec.value = settings.backtrackSec;
  inputs.backtrackSecVal.textContent = `${settings.backtrackSec} 秒`;
  inputs.guideV.checked = settings.guideV;
  inputs.guideH.checked = settings.guideH;
  inputs.slowMoRate.value = String(settings.slowMoRate);
  inputs.dominantHand.value = settings.dominantHand;
}

function readSettingsFromInputs() {
  return {
    delaySec: Number(inputs.delaySec.value),
    backtrackSec: Number(inputs.backtrackSec.value),
    guideV: inputs.guideV.checked,
    guideH: inputs.guideH.checked,
    slowMoRate: Number(inputs.slowMoRate.value),
    dominantHand: inputs.dominantHand.value,
  };
}

function onSettingsChanged() {
  const next = readSettingsFromInputs();
  settingsStore.set(next);
  saveSettings(settingsStore.get());
  applyBufferWindow(settingsStore.get());
  applyPipPosition(settingsStore.get());
  inputs.delaySecVal.textContent = `${next.delaySec} 秒`;
  inputs.backtrackSecVal.textContent = `${next.backtrackSec} 秒`;
}

Object.values(inputs).forEach((el) => {
  if (el.tagName === 'INPUT' || el.tagName === 'SELECT') {
    el.addEventListener('input', onSettingsChanged);
  }
});

populateSettingsInputs(settingsStore.get());
applyBufferWindow(settingsStore.get());
applyPipPosition(settingsStore.get());

// ---------- app state machine ----------
function setAppState(next) {
  appState = next;
  const locked = next !== APP_STATE.LIVE;
  gestureEngine.setLocked(locked);
  btnManualSave.disabled = locked;
}

async function triggerSave() {
  if (appState !== APP_STATE.LIVE) return;
  const settings = settingsStore.get();
  const now = performance.now();
  const frames = frameBuffer.getFramesInRange(now - settings.backtrackSec * 1000, now);

  if (frames.length < 2) {
    toast('緩衝畫面還不夠,請再等一下');
    return;
  }

  setAppState(APP_STATE.SAVING);

  const createdAt = Date.now();
  const filename = clipFilename(new Date(createdAt));
  const durationMs = frames[frames.length - 1].ts - frames[0].ts;

  // Background export for the gallery - the on-screen replay does not wait on this.
  encodeClip(frames)
    .then((blob) => {
      const thumbCanvas = document.createElement('canvas');
      thumbCanvas.width = 160;
      thumbCanvas.height = 90;
      thumbCanvas.getContext('2d').drawImage(frames[0].canvas, 0, 0, 160, 90);
      return gallery.addClip({
        filename,
        createdAt,
        durationMs,
        blob,
        thumbnail: thumbCanvas.toDataURL('image/jpeg', 0.7),
      });
    })
    .then(() => toast(`已存檔:${filename}`))
    .catch((err) => {
      console.error('[save] failed', err);
      toast('存檔失敗:儲存空間不足或裝置不支援錄影,請清理空間後再試');
    });

  showScreen(SCREEN.REPLAY);
  setAppState(APP_STATE.REPLAY);
  replayCancel.requested = false;
  replayProgressFill.style.width = '0%';

  await playFrames(replayCanvas, frames, settings.slowMoRate, {
    onProgress: (p) => { replayProgressFill.style.width = `${Math.round(p * 100)}%`; },
    cancel: replayCancel,
  });

  setAppState(APP_STATE.LIVE);
  showScreen(SCREEN.LIVE);
}

btnManualSave.addEventListener('click', () => triggerSave());
btnReturnNow.addEventListener('click', () => { replayCancel.requested = true; });

// ---------- gallery ----------
async function renderGallery() {
  const clips = await gallery.listClips();
  galleryListEl.innerHTML = '';
  galleryEmptyEl.classList.toggle('hidden', clips.length > 0);

  for (const clip of clips) {
    const card = document.createElement('div');
    card.className = 'clip-card';
    card.innerHTML = `
      <img src="${clip.thumbnail}" alt="${clip.filename}" />
      <div class="clip-card__meta">
        <p class="clip-card__name">${clip.filename}</p>
        <p class="clip-card__sub">${formatClock(clip.createdAt)} · ${formatDuration(clip.durationMs)}</p>
      </div>
      <div class="clip-card__actions">
        <button data-act="play">播放</button>
        <button data-act="delete">刪除</button>
      </div>
    `;
    card.querySelector('[data-act="play"]').addEventListener('click', () => openPlayer(clip));
    card.querySelector('[data-act="delete"]').addEventListener('click', async (e) => {
      e.stopPropagation();
      await gallery.deleteClip(clip.id);
      renderGallery();
    });
    card.addEventListener('click', () => openPlayer(clip));
    galleryListEl.appendChild(card);
  }
}

function openPlayer(clip) {
  currentPlayerClipId = clip.id;
  playerTitle.textContent = clip.filename;
  const url = URL.createObjectURL(clip.blob);
  playerVideo.src = url;
  playerSlowMo.checked = false;
  playerVideo.playbackRate = 1;
  btnDownloadClip.href = url;
  btnDownloadClip.download = clip.filename;
  showScreen(SCREEN.PLAYER);
  playerVideo.play().catch(() => {});
}

playerSlowMo.addEventListener('change', () => {
  playerVideo.playbackRate = playerSlowMo.checked ? settingsStore.get().slowMoRate : 1;
});

btnDeleteClip.addEventListener('click', async () => {
  if (!currentPlayerClipId) return;
  await gallery.deleteClip(currentPlayerClipId);
  currentPlayerClipId = null;
  showScreen(SCREEN.GALLERY);
  renderGallery();
});

// ---------- camera bootstrap ----------
async function initCamera() {
  // getUserMedia only exists in a secure context (https://, or http:// on
  // localhost/127.0.0.1). Opening this over plain http://<lan-ip> - the most
  // likely way someone tries it on a real phone - silently has no
  // mediaDevices object at all, which is a very different fix (serve over
  // HTTPS or localhost) from "permission denied", so call it out explicitly
  // instead of letting it fall into the generic catch below.
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    toast('無法使用相機:此網址不是安全連線 (https 或 localhost)。請改用 HTTPS 網址,或在手機上以 localhost 開啟', 8000);
    return;
  }
  try {
    // Front camera: the whole point of the delay mirror (spec 2.1) is the
    // archer looks at the screen like a mirror, which only reads correctly
    // if the camera facing them is the one doing the filming.
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'user' }, width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: false,
    });
    camSource.srcObject = stream;
    pipVideo.srcObject = stream;
    await camSource.play();
    await pipVideo.play();
  } catch (err) {
    console.error('[camera] failed', err);
    toast('無法取得相機權限,請允許相機存取後重新整理', 5000);
  }
}

// ---------- background/foreground lifecycle (spec 9.1) ----------
// Chosen policy: pause capture + detection while backgrounded; on return,
// the buffer is stale so it is cleared and the delay mirror re-warms up.
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    frameBuffer.stop();
    delayRenderer.stop();
    gestureEngine.stop();
  } else {
    frameBuffer.clear();
    frameBuffer.start();
    delayRenderer.start();
    if (gestureEngine.available && appState === APP_STATE.LIVE) gestureEngine.start();
  }
});

// ---------- boot ----------
(async function boot() {
  await initCamera();
  frameBuffer.start();
  delayRenderer.start();
  await gestureEngine.init(camSource);
  gestureEngine.start();
})();
