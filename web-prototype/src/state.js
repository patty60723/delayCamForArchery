// Shared constants + tiny pub/sub store for settings and app state.
// Defaults mirror spec section 4 (設定項目清單).

export const DEFAULT_SETTINGS = Object.freeze({
  delaySec: 5,
  backtrackSec: 8,
  guideV: true,
  guideH: true,
  slowMoRate: 0.5,
  dominantHand: 'right', // 'left' | 'right'
});

export const APP_STATE = Object.freeze({
  LIVE: 'live',
  SAVING: 'saving',
  REPLAY: 'replay',
});

export const SCREEN = Object.freeze({
  LIVE: 'live',
  REPLAY: 'replay',
  SETTINGS: 'settings',
  GALLERY: 'gallery',
  PLAYER: 'player',
});

export class Store {
  constructor(initial) {
    this._data = { ...initial };
    this._listeners = new Set();
  }
  get(key) {
    return key === undefined ? this._data : this._data[key];
  }
  set(patch) {
    Object.assign(this._data, patch);
    for (const fn of this._listeners) fn(this._data, patch);
  }
  subscribe(fn) {
    this._listeners.add(fn);
    return () => this._listeners.delete(fn);
  }
}
