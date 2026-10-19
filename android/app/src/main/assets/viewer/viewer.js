'use strict';
const film = document.querySelector('#film'), hold = document.querySelector('#hold');
const status = document.querySelector('#status'), notice = document.querySelector('#notice');
const start = document.querySelector('#start'), welcome = document.querySelector('#welcome');
let running = false, state = null, current = null, epoch = null, generation = 0;
let loading = false, mode = null, receivedAt = 0, reconnect = false;
let pollTimer = null, polling = false, pollController = null;
const say = text => { if (status.textContent !== text) status.textContent = text; };
const message = text => { notice.hidden = !text; if (notice.textContent !== text) notice.textContent = text; };
const hostNow = () => state.nowMs + performance.now() - receivedAt;
function scalePicture() {
  const w = film.clientWidth, h = film.clientHeight, e = film.videoWidth / film.videoHeight;
  if (!(w > 0 && h > 0 && e > 0)) return;
  const a = current?.aspect > 0 ? current.aspect : e;
  const fw = Math.min(w, h * e), pw = fw * Math.min(1, a / e), ph = fw / e * Math.min(1, e / a);
  const choice = mode || state?.pictureMode || 'FIT';
  const zoom = choice === 'FILL' ? Math.max(w / pw, h / ph) : Math.min(w / pw, h / ph);
  document.querySelector('#stage').style.setProperty('--scale', Math.max(1, zoom));
  document.querySelector('#fit').setAttribute('aria-pressed', String(choice === 'FIT'));
  document.querySelector('#fill').setAttribute('aria-pressed', String(choice === 'FILL'));
}
function freezeFrame() {
  if (film.readyState >= 2 && film.videoWidth) {
    hold.width = film.videoWidth; hold.height = film.videoHeight;
    hold.getContext('2d').drawImage(film, 0, 0);
    hold.hidden = false;
  }
  film.style.opacity = '0';
}
function reset() {
  freezeFrame(); generation++; loading = false; current = null;
  film.pause(); film.removeAttribute('src'); film.load();
}
function mediaReady(event, timeout = 8000) {
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); film.removeEventListener(event, ok); film.removeEventListener('error', fail); };
    const ok = () => { cleanup(); resolve(); };
    const fail = () => { cleanup(); reject(new Error('media')); };
    const timer = setTimeout(() => { cleanup(); reject(new Error('timeout')); }, timeout);
    film.addEventListener(event, ok, {once: true}); film.addEventListener('error', fail, {once: true});
  });
}
async function load(segment, positionMs) {
  if (loading) return;
  freezeFrame(); loading = true; current = segment; const version = ++generation;
  try {
    const ready = mediaReady('loadedmetadata');
    film.src = `segment/${segment.id}.mp4`; film.load(); await ready;
    if (version !== generation || !running) return;
    film.currentTime = Math.max(0, Math.min(positionMs / 1000, Math.max(0, film.duration - .05)));
    scalePicture();
    await film.play();
    if (version !== generation || !running) return;
    if (film.requestVideoFrameCallback) {
      await new Promise((resolve, reject) => {
        const id = film.requestVideoFrameCallback(() => { clearTimeout(timer); resolve(); });
        const timer = setTimeout(() => { film.cancelVideoFrameCallback(id); reject(new Error('frame-timeout')); }, 4000);
      });
    } else if (film.readyState < 2) await mediaReady('loadeddata');
    if (version !== generation || !running) return;
    film.style.opacity = '1'; hold.hidden = true;
    welcome.hidden = true; message(''); say(`延遲觀看 · 設定 ${state.delaySec} 秒`);
  } catch (error) {
    if (version !== generation) return;
    current = null;
    if (error.name === 'NotAllowedError' || error.name === 'NotSupportedError') {
      running = false; welcome.hidden = false; start.disabled = false; start.textContent = '再次開始觀看';
      say(error.name === 'NotSupportedError' ? '無法解碼影片，請使用最新版 Chrome／Edge' : '請點擊開始觀看');
    } else { reconnect = true; message('影片暫時無法播放，正在接回目前延遲。'); }
  } finally { if (version === generation) loading = false; }
}
function sourceTime() {
  if (!current || !(current.durationMs > 0)) return null;
  return current.startMs + Math.min(1, film.currentTime * 1000 / current.durationMs) * (current.endMs - current.startMs);
}
function advance() {
  if (!state || !running || loading || !state.active) return;
  const target = hostNow() - state.delaySec * 1000;
  const source = sourceTime();
  // Bound accumulated inter-segment stalls rather than slowly replaying expired history.
  if (source !== null && target - source > 1500) { reset(); reconnect = true; }
  if (current && !film.ended && !reconnect) return;
  let segment, position = 0;
  if (current && !reconnect) {
    segment = state.segments.find(s => s.id > current.id && s.startMs >= current.endMs - 100);
    if (segment && segment.startMs - current.endMs > 1500) { reconnect = true; segment = null; }
    if (segment && segment.startMs > target) return;
  }
  if (!segment && current && target - current.endMs > 1500) reconnect = true;
  if (!segment && (!current || reconnect)) {
    segment = state.segments.find(s => s.startMs <= target && s.endMs > target);
    if (segment) position = (target - segment.startMs) / Math.max(1, segment.endMs - segment.startMs) * segment.durationMs;
  }
  if (!segment) {
    const first = state.segments[0];
    const seconds = first ? Math.max(0, Math.ceil((first.startMs + state.delaySec * 1000 - hostNow()) / 1000)) : state.delaySec;
    message(seconds > 0 ? `建立延遲畫面 · 約 ${seconds} 秒` : '等待下一個攝影片段'); return;
  }
  reconnect = false; load(segment, position);
}
async function poll() {
  clearTimeout(pollTimer);
  if (document.hidden || polling) return;
  polling = true;
  try {
    pollController = new AbortController();
    const timer = setTimeout(() => pollController?.abort(), 4000), requestedAt = performance.now();
    let response;
    try { response = await fetch('status', {cache: 'no-store', signal: pollController.signal}); }
    finally { clearTimeout(timer); }
    if (document.hidden) return;
    if (!response.ok) throw new Error('connection');
    const next = await response.json();
    if (next.protocol !== 1 || !Array.isArray(next.segments) || !Number.isFinite(next.nowMs)) throw new Error('protocol');
    if (epoch !== next.epoch || state && state.delaySec !== next.delaySec) { reset(); reconnect = true; }
    state = next; epoch = next.epoch; receivedAt = performance.now();
    state.nowMs += (receivedAt - requestedAt) / 2;
    document.querySelector('#timing').textContent = `拍攝端延遲設定 ${state.delaySec} 秒；傳輸與片段交接可能增加等待。`;
    if (!state.active) {
      reset(); say('拍攝端已暫停'); if (running) message('請在拍攝手機確認分享狀態；若服務已停止，請重新開啟並使用新連結。'); return;
    }
    if (running) {
      scalePicture(); advance();
      const time = sourceTime();
      document.querySelector('#age').textContent = time === null || loading ? '' : `目前影像約 ${Math.max(0, (hostNow() - time) / 1000).toFixed(1)} 秒前`;
    } else say('已連線 · 點擊開始觀看');
  } catch (_) {
    if (document.hidden) return;
    if (running) { reset(); reconnect = true; message('連線中斷，顯示最後畫面。正在重連，請確認拍攝端與網路。'); }
    say('連線中斷 · 重新開啟分享後，請使用新連結');
    document.querySelector('#age').textContent = '';
  } finally {
    polling = false; pollController = null;
    if (!document.hidden) pollTimer = setTimeout(poll, 750);
  }
}
start.addEventListener('click', () => { running = true; start.disabled = true; reconnect = true; message('正在準備延遲畫面'); advance(); });
film.addEventListener('ended', advance);
film.addEventListener('error', () => { if (!loading && running) { current = null; reconnect = true; message('片段已過期或暫時無法播放，正在接回。'); } });
document.querySelector('#fit').addEventListener('click', () => { mode = 'FIT'; scalePicture(); });
document.querySelector('#fill').addEventListener('click', () => { mode = 'FILL'; scalePicture(); });
document.querySelector('#resume').addEventListener('click', () => { reset(); reconnect = true; advance(); });
const toggle = document.querySelector('#toggle'), panel = document.querySelector('#panel');
toggle.addEventListener('click', () => { panel.hidden = !panel.hidden; toggle.setAttribute('aria-expanded', String(!panel.hidden)); toggle.textContent = panel.hidden ? '操作' : '收起'; });
document.addEventListener('keydown', event => { if (event.key === 'Escape' && !panel.hidden) { panel.hidden = true; toggle.setAttribute('aria-expanded', 'false'); toggle.textContent = '操作'; toggle.focus(); } });
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { clearTimeout(pollTimer); pollController?.abort(); reset(); reconnect = true; }
  else { reconnect = true; if (!polling) poll(); }
});
window.addEventListener('resize', scalePicture);
poll();
