# Required model asset

`GestureEngine` (see `gesture/GestureEngine.kt`) loads a MediaPipe gesture
recognizer model from `assets/gesture_recognizer.task`, which is **not**
checked into this repo (it's a ~10MB binary).

Download it and place it in this folder:

```
https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task
```

(the same model the web prototype fetches at runtime from Google's CDN -
see the repo root README's comparison table). Target filename:

```
app/src/main/assets/gesture_recognizer.task
```

Without it, `GestureEngine.init()` fails gracefully and the app falls back
to manual-save-button-only, per spec 3.1 - it will still run and the rest
of the app (delay mirror, backtrack save, replay, gallery) is unaffected.
