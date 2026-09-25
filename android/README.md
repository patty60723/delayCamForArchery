# 射箭 Delay Camera — Native Android Skeleton

Kotlin + Jetpack Compose 專案骨架,依規格書 2–6 節實作:CameraX 相機管線、
spec 5.2 的短片段循環緩衝、ExoPlayer 播放清單做出的延遲鏡子、MediaPipe
GestureRecognizer 全畫面手勢辨識、MediaMuxer 存檔匯出、MediaStore 畫廊。

## ⚠️ 目前的驗證狀態(請先讀這段)

這份專案是在**沒有 Android SDK / build tools 下載權限**的沙盒環境裡寫出來的
(`dl.google.com` 被網路政策擋掉,AndroidX / CameraX / MediaPipe / Media3 的
套件全部抓不到)。實際做了這些驗證,但都有上限:

- ✅ `settings.gradle.kts` / `build.gradle.kts` 用本機 Gradle 8.14.3 實際跑過
  `./gradlew projects`,確認 Kotlin DSL 語法正確、Gradle wrapper(`gradle-wrapper.jar`)
  是真的用 `gradle wrapper` 指令產生的,不是手刻的殼;卡在抓 Android Gradle
  Plugin 那一步(預期中的失敗,原因就是上面說的網路限制)。
- ✅ 全部 24 個 `.kt` 檔用 Gradle 內附的 Kotlin 編譯器(`kotlin-compiler-embeddable`)
  個別跑過語法檢查。掃出來的錯誤清一色是「找不到符號」(因為 Android SDK / AndroidX
  等套件不在 classpath 上,預期中的),沒有發現真正的語法錯誤(括號不對、關鍵字打錯等)。
- ❌ **完全沒有真的 build 過、更沒有在裝置或模擬器上跑過一次。** 邏輯是照著已經在瀏覽器
  裡驗證過的網頁版 prototype(`/index.html` 等)的行為去translate,但 Android 原生
  API(CameraX Recorder、MediaMuxer 串接、MediaPipe Android AAR、ExoPlayer 播放清單)
  的實際行為只能等你在 Android Studio(或見下方 VS Code 那條路)裡 build 出來、
  裝到實機上跑才知道。
- ❌ **`build.gradle.kts` 裡的依賴版本號也是憑既有知識寫的,沒有逐一對過真的
  存在。** 已經實際踩到一次(`media3-ui-compose:1.4.1` 解析不到,而且整個專案
  根本沒用到它,直接刪掉了事);後續 sync 如果再冒出其他 `Failed to resolve`,
  是預期中可能發生的事,排查方式見
  [`docs/android-studio-setup.md`](docs/android-studio-setup.md) 疑難排解那節。

換句話說:這是一份「骨架」,不是「能動的 App」——架構、檔案分工、對應規格的邏輯都在,
但請預期第一次 build/跑會需要除錯。下面列出我認為風險最高、最可能需要修的幾個地方。

## 已知風險最高的地方(建議優先實機測試/除錯)

1. **`camera/ContinuousSegmentEncoder.kt`** — 早期版本用 CameraX `Recorder` 的
   stop()+start() 循環切片段檔案,實機測試後在 logcat 證實這會讓相鄰片段的 H.264
   SPS(編碼參數)真的不一致("Inconsisted profile/level found in SPS"),導致延遲
   畫面在片段交界處卡死/黑畫面。改成自己接管 `MediaCodec` 後,試過兩種餵畫面的
   方式都在實機踩到問題:(1) 另外綁一個 `Preview` use case,結果直接綁定失敗
   ("No supported surface combination")——這台裝置的前鏡頭不支援兩個 `Preview`
   類型的 stream 同時跟 `ImageAnalysis` 並存;(2) 改成不開新 stream、直接吃
   `ImageAnalysis` 的畫面,但用 buffer 模式手動複製 YUV plane 進 `MediaCodec`,
   結果每一幀都丟 `BufferOverflowException`。現在改成:一樣吃 `ImageAnalysis`
   的畫面(不開新相機 stream),但透過 `GestureEngine` 手勢辨識本來就在用、已經
   驗證能動的 YUV→Bitmap 轉換(`camera/ImageProxyConversion.kt`),把轉好的
   Bitmap 用 `Canvas` 畫到編碼器的 Surface 上,而不是自己手刻位元組複製。這個
   版本也還沒有實機驗證過,要留意畫面顏色/方向對不對;另外因為共用了 JPEG 編解碼
   的轉換路徑,畫質跟效能會比理論上直接餵 YUV 差一些,先求能動再優化。另外 60fps
   (規格 5.1)目前沒有透過 `Camera2Interop` 釘死 frame rate range,先跑起來看看
   裝置預設幀率再說。

2. **`camera/ClipExporter.kt`** — 把多個片段檔案用 `MediaExtractor`+`MediaMuxer`
   串接成一支輸出 MP4。現在片段都來自同一個持續運作的編碼器 session,編碼參數應該
   一致,串接處出包的機率理論上比舊版低很多,但這個假設也還沒有實機驗證過。

3. **`camera/DelayedPlaybackController.kt`** — 用「N 秒後才把片段加進 ExoPlayer
   播放清單」做出延遲鏡子效果,是很直接地照規格 5.2 字面意思實作,但沒實測過
   片段間切換會不會有明顯頓挫,也沒測過 `delaySec` 設定中途變更時的行為。

4. **`gesture/GestureEngine.kt`** 的 YUV→NV21 轉換(`toBitmapViaYuv()`)假設
   U/V 平面 `pixelStride == 1`,多數裝置沒問題但不保證每台都這樣;如果手勢辨識完全
   抓不到手(而非只是不準),換成有考慮 pixelStride 的轉換法。

5. **`camera/ContinuousRecordingSession.kt`**(連續錄影)— 做法是重用同一顆
   rolling buffer 的片段,錄影期間每個片段一 finalize 就即時 append 進同一支還沒
   關閉的 `MediaMuxer`,跟 `ClipExporter` 同樣的串接邏輯只是拆成一段一段做,所以
   風險跟第 2 點是同一類(關鍵幀沒對齊)。此外用 `Mutex` 序列化 append/stop 呼叫
   避免併發寫壞 muxer 狀態,這個序列化邏輯本身也還沒有實機測過。

## 建置步驟

兩條路線都是開 `android/` 這個資料夾(不是整個 repo 根目錄),完整步驟(含
Gradle sync 常見卡關狀況、接裝置、Run、看 log、疑難排解)分別寫在:

- [`docs/android-studio-setup.md`](docs/android-studio-setup.md) — 最省事,
  Android Studio 會自動幫你裝好 JDK 與大半的 SDK 元件。
- [`docs/vscode-setup.md`](docs/vscode-setup.md) — 不想裝 Android Studio、只想
  用 VS Code + 命令列的話走這條,但要自己手動裝 SDK 命令列工具,而且沒有中斷點
  除錯、Logcat 視窗等 Studio 內建工具。

手勢辨識模型(`gesture_recognizer.task`)兩條路線都不用另外下載,已經包在
`app/src/main/assets/` 裡了。minSdk 設 29(Android 10+,2019 年後的裝置大多
沒問題),沒有特別要求機型或廠牌。

## 對應規格書章節

| 檔案 | 對應規格 |
| --- | --- |
| `camera/ContinuousSegmentEncoder.kt` + `SegmentBuffer.kt` | 2.1 / 5.2 常駐延遲緩衝 |
| `camera/DelayedPlaybackController.kt` | 2.1 / 5.2 延遲鏡子播放 |
| `camera/ClipExporter.kt` + `MediaStoreExport.kt` | 2.2 步驟1 / 6 回溯存檔與檔案管理 |
| `camera/ContinuousRecordingSession.kt` | 連續錄影開關(新增,非規格原有項目) |
| `gesture/GestureEngine.kt` | 2.2 / 5.3 / 9.1 全畫面手勢辨識(張開手掌存檔 + 讚連續錄影兩個獨立手勢)、穩定門檻、冷卻 |
| `ui/live/LiveScreen.kt` | 3.1 主畫面 + 即時小視窗 PiP + REC 錄影提示 |
| `ui/replay/ReplayScreen.kt` | 3.2 慢動作回放畫面 |
| `ui/settings/SettingsScreen.kt` + `settings/*` | 3.3 / 4 設定畫面與項目 |
| `ui/gallery/GalleryScreen.kt` + `PlayerScreen.kt` | 3.4 畫廊 |
| `ui/DelayCamViewModel.kt` | 2.2 LIVE/SAVING/REPLAY 狀態機,串起以上所有模組 |

手勢改成全畫面偵測(而非固定角落 ROI)、主畫面加即時小視窗 PiP、以及連續錄影開關,
都是照使用者在網頁版 prototype 測試後給的回饋帶過來的設計決定,不是規格書原文,
細節說明見 [`../web-prototype/README.md`](../web-prototype/README.md) 的對照表。
