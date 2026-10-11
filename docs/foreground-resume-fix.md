# 返回前景後延遲畫面凍結：修正版 2

日期：2026-10-11。這是 [第一版播放接續修正](delay-playback-fix-technical-notes.md) 的後續修正。

## 重現與判斷

使用者回報：第一版修正版正常使用時，原本的黑屏與反覆暖機暫未再發生；但滑到桌面再返回 App，delay 畫面停在離開前的影像。

CameraX 透過 `bindToLifecycle(Activity)` 綁定相機，Activity 進入背景時會停止送入新影格，再返回時才恢復。原 `MainActivity` 沒有把這個切換通知 `DelayCamViewModel`，編碼器、片段排程、緩衝與播放器仍保留背景前的狀態。

因此原程式可能把暫停前後的影像和時間戳接在同一段錄影中，或在返回時繼續使用舊播放清單。畫面凍結也不必然等同於 `currentPosition` 不變，單靠播放位置 watchdog 不足以處理整條管線的生命週期。這些是來源碼可確認的處理缺口與可能的失敗路徑；本次尚未取得 Pixel 的重現 Logcat，不能據此斷言每次凍結都由同一個原因造成。

## 修正行為

1. `MainActivity.onStop()` 通知 ViewModel 進入背景，立刻停止接受影格，暫停回放，清掉延遲播放清單、Handler 排程與緩衝。
2. 編碼器停止與釋放安排在相機分析的同一個單執行緒 executor，排在正在處理的影格之後，避免在 Canvas 繪製途中釋放輸入 Surface，也避免在 Activity 主執行緒等待編碼器結束。
3. `MainActivity.onStart()` 開啟新的 capture session，在同一 executor 的停止工作之後建立新的編碼器，再接受影格。每個新 session 會重新累積一次延遲畫面。
4. 舊 session 的影格、延遲啟動工作與片段完成回呼會被拒絕，不得重新填入新 session 的緩衝或播放清單。
5. 若離開前正在慢動作回放，背景時先暫停；返回後可在原回放位置繼續。LIVE 延遲來源仍以新的 session 重建。

這次沒有新增背景相機服務。背景期間不會拍攝新影像；連續錄影功能的原有狀態仍保留，錄製內容不包含背景期間的畫面。連續錄影與慢動作回放的實機回歸仍需另行確認。

切回前景會出現一次「準備延遲畫面」倒數，這是重新取得新影像的預期行為。持續停在前景時，不應反覆重置或永遠停在舊畫面。

## Session 防護與資源清理

`ForegroundCaptureSession` 使用同步方法協調 Activity 主執行緒與相機 executor。只有目前仍在前景、且已完成啟動的 session 才可接受影格；每次背景／前景往返會取得新 ID。即使快速反覆切換，已失效 session 的啟動工作也不能再啟用相機影格處理。

```text
onStop → session 失效、清空舊播放狀態 → executor 停止舊編碼器
onStart → 新 session → executor 建立新編碼器 → 標記 ready
→ 接受新影格 → 產生新片段 → 累積延遲 → 正常播放
```

`MainActivity.onDestroy()` 清除該 Activity 的 ImageAnalysis analyzer。executor 由 ViewModel 持有，能在 Activity 重建時維持停止／啟動工作的順序；ViewModel 真正清除時才安排最終清理並關閉 executor。

`ContinuousSegmentEncoder.stop()` 現在也釋放輸入 Surface；codec 的 stop／release 例外會被記錄。drain loop 的 `releaseOutputBuffer()` 例外亦會被捕捉，避免停止期間的 codec 釋放競爭造成未捕捉例外。若 drain thread 超過原有 2 秒等待時間仍存活，會記錄警告；這不是完整解決所有底層編碼器阻塞的保證。

## 播放 watchdog 與片段 ID

第一版 ENDED seek 接續與排除 ENDED 的 watchdog 修正保留。Watchdog 現在另要求 App 在前景，避免背景期間的正常暫停觸發錯誤恢復。

同時完成第一版文件提到的片段 ID 改進：每個 MediaItem 顯式設定 `檔案絕對路徑:startedAtMs:endedAtMs` 為 `mediaId`。這樣 ring buffer 重用檔名時仍能區分不同次錄影，也讓跨片段進度判斷與日誌有可用的片段身分。

## APK 與建置

| 項目 | 修正版 2 |
| --- | --- |
| 分支 | `codex/apk-delay-playback-fix` |
| App 名稱 | 射箭 Delay Camera 修正版 2 |
| application ID | `com.archery.delaycam.delayfix2` |
| versionName / versionCode | `0.1.0-skeleton-delayfix2` / `2` |
| Artifact 名稱 | `delaycam-foreground-fix-apk` |
| 建置命令 | `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace` |
| 安裝檔 | Artifact ZIP 內的 `app-debug.apk` |

修正版 2 使用獨立 application ID，可與原版及修正版 1 並存。安裝後請選擇名稱帶有「修正版 2」的 App 測試；它不會自動承接另一版本的私有設定。簽章仍為 CI 產生的 debug 簽章，後續建置不能保證直接覆蓋更新。

## 驗證範圍

新增七個 JVM 單元測試，驗證 session 在初次啟動、進入背景、返回前景、重複通知、快速往返及永久清除時，正確拒絕舊工作並只接受目前 session 的影格。這些測試驗證 session 協調邏輯；不會模擬 CameraX、TextureView 或 Pixel 的 MediaCodec。

GitHub Actions 已完成單元測試、編譯 APK，並以 `apksigner` 驗證簽章：

| 項目 | 結果 |
| --- | --- |
| APK 來源 commit | `219166d5f6031b34fb8f877bdf4fde5a00b21f72` |
| Actions run | [38141418090](https://github.com/patty60723/delayCamForArchery/actions/runs/38141418090) |
| JVM 單元測試 | 7 個，failures 0、errors 0、skipped 0 |
| 編譯 | `BUILD SUCCESSFUL in 2m 18s` |
| 簽章 | `Verifies`，APK Signature Scheme v2 為 true |
| APK 下載 | [delaycam-foreground-fix-apk](https://github.com/patty60723/delayCamForArchery/actions/runs/38141418090/artifacts/11703382673)，ZIP 約 40 MB |
| Artifact 到期 | 2026-10-25 13:02:35 UTC |
| Pixel 實機 | 尚待使用者安裝修正版 2 後驗證 |

解壓縮後 `app-debug.apk` 的 SHA-256：

```text
7162173ec9bd4613feb7d704fd958834ac07c5cbb75793e3bea8d8255934f7b8
```

以上 CI 驗證通過尚不代表 Pixel 實機的背景／前景往返已驗證通過。只有文件的後續 commit 不會改變已交付 APK 的來源版本。

實機驗收：

1. 首次開啟後等暖機完成，確認 LIVE 持續播放。
2. 滑到桌面停留 5–10 秒，再返回；一次重新暖機後應顯示新的動作。
3. 在桌面停留超過設定的延遲時間，再返回，確認沒有一直顯示離開前的影像。
4. 快速往返桌面 3–5 次，以及鎖屏再解鎖，確認最後一次返回後能恢復。
5. 另測慢動作回放、連續錄影及螢幕旋轉；這些屬於尚待裝置驗證的回歸項目。

若仍凍結，收集 Logcat：`package:com.archery.delaycam.delayfix2`。除了 `segment finalized:`、`livePlayer state=`、`livePlayer stalled:`，也觀察 `app foreground: capture session=`、`app background:`、`drain thread still finishing` 與 `foreground encoder cleanup failed`。記錄離開與返回時間，才能分辨新片段沒有產生、片段有產生但播放器卡住，以及顯示 Surface 的問題。
