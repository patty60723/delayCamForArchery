# 射箭 Delay Camera

讓射手獨自練習時，手機立在旁邊持續顯示「幾秒前的自己」：放箭後轉頭一看就能檢視動作，不用等教練或事後翻整支影片。

**技術**：Kotlin · Jetpack Compose · CameraX · MediaPipe Gesture Recognizer · MediaCodec／MediaMuxer · Android 10 以上（API 29）· GitHub Actions 打包 APK 並在模擬器跑 instrumentation 測試

**亮點**
- **常駐延遲畫面**：持續錄影、延遲幾秒播放，放箭後回頭就看得到剛才的動作
- **隔空手勢操作**：比個手勢就能存下剛才那一箭、控制回放，拉弓時不用碰手機
- **慢動作回放與畫廊**：存下的片段可以慢放檢視，也能匯出到相簿
- **先驗證、再實作**：互動手感先在網頁 prototype 試過，再實作進原生 App

## [`android/`](android/) — 正式版（Kotlin 原生 App）

Kotlin + Jetpack Compose + CameraX + MediaPipe GestureRecognizer + MediaCodec/MediaMuxer，
實作常駐延遲緩衝、手勢／按鈕存檔、慢動作回放、設定、畫廊。手勢辨識模型已經包在 repo 裡，不用另外下載。
建置方式、開發筆記與除錯紀錄見 [`android/README.md`](android/README.md)；產品、UX 與系統設計文件在 [`docs/`](docs/)。

## [`web-prototype/`](web-prototype/) — 開發用網頁 prototype(非正式版)

純前端(瀏覽器 Canvas + IndexedDB)做的互動 mockup,拿來在**開發過程中**快速
驗證 UX/互動流程用的——不用開 Android Studio、不用裝置,瀏覽器打開就能點過一次
完整流程。全畫面手勢偵測、即時小視窗 PiP 這兩個設計決定,都是先在這裡驗證過
手感才回頭套進正式版的。細節、已知限制、與正式版的差異對照表見
[`web-prototype/README.md`](web-prototype/README.md)。

之後如果還要再驗證新的互動流程想法,建議先在這裡改著試,比每次都重新 build
原生 App 快很多;但**不要拿這個當成最終驗收標準**,效能、真實鏡頭/手勢辨識的
表現都得以 `android/` 為準。
