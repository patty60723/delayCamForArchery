# 射箭 Delay Camera

讓射手獨自練習時,手機立在旁邊持續顯示「幾秒前的自己」——放箭後轉頭一看就能
立刻檢視動作,不用等教練或賽後回看整支影片。完整產品邏輯見「射箭 Delay
Camera App — 產品規格書」;這個 repo 目前有兩個部分:

## [`android/`](android/) — 正式版(Kotlin 原生 App)

**這是正確的版本。** Kotlin + Jetpack Compose + CameraX + MediaPipe
GestureRecognizer + MediaCodec/MediaMuxer,照規格書實作常駐延遲緩衝、手勢/按鈕
存檔、慢動作回放、設定、畫廊。手勢辨識模型已經包在 repo 裡,不用另外下載。

⚠️ 骨架完整,但目前**還沒有在真正的 Android SDK / 裝置上 build 或跑過一次**
(寫這份骨架的環境下載不到 Android SDK)——在 Android Studio 裡開起來、裝到
實機之前,請先讀 [`android/README.md`](android/README.md),裡面列了目前
做過的驗證範圍,以及最可能需要除錯的幾個地方(片段錄影銜接、MediaMuxer 串接、
延遲播放清單、YUV 轉換)。

## [`web-prototype/`](web-prototype/) — 開發用網頁 prototype(非正式版)

純前端(瀏覽器 Canvas + IndexedDB)做的互動 mockup,拿來在**開發過程中**快速
驗證 UX/互動流程用的——不用開 Android Studio、不用裝置,瀏覽器打開就能點過一次
完整流程。全畫面手勢偵測、即時小視窗 PiP 這兩個設計決定,都是先在這裡驗證過
手感才回頭套進正式版的。細節、已知限制、與正式版的差異對照表見
[`web-prototype/README.md`](web-prototype/README.md)。

之後如果還要再驗證新的互動流程想法,建議先在這裡改著試,比每次都重新 build
原生 App 快很多;但**不要拿這個當成最終驗收標準**,效能、真實鏡頭/手勢辨識的
表現都得以 `android/` 為準。
