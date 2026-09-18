# Model asset

`GestureEngine` (see `gesture/GestureEngine.kt`) loads a MediaPipe gesture
recognizer model from `assets/gesture_recognizer.task`. That file **is**
checked into this folder (~8MB), fetched from:

```
https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task
```

(the float16 build - same model the earlier web prototype fetched at
runtime from Google's CDN, see `web-prototype/README.md`). If you need to
refresh it or swap in a different precision/variant, re-download from that
URL (or MediaPipe's model index) and overwrite this file - it's a plain
binary, no build step needed to pick it up.

If this file is ever missing or fails to load, `GestureEngine.init()` fails
gracefully and the app falls back to manual-save-button-only, per spec
3.1 - it will still run and the rest of the app (delay mirror, backtrack
save, replay, gallery) is unaffected.
