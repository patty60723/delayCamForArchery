# 錄影與基本回放介面：修正版 3

## 範圍與行為

依使用者核可的 PM／UI UX 建議，先完成錄影與基本播放。逐格、A-B 循環、標記及日期分組不在這一批。

- 頂部錄影列跨頁共用：開始／停止／繼續錄影、計時、保存結果與查看入口。
- 切背景停止並保存當前錄影段；返回重建延遲畫面，不自動續錄。旋轉重建 Activity 同樣處理。
- 錄影指令透過 Channel 依序處理，停止先等待尾段或編碼器結束（以 EOS 排出已送入的畫格）；5 秒無完成事件時保存已取得內容，明示有片段未確認。
- MediaStore 完成後才顯示成功；失敗保留可重試副本，並移除未完成的相簿項目。
- 保存單箭保留「存檔並回看」，另加「只保存」；兩者都不停止整段錄影。
- 單箭使用獨立快照，避免暫停回放期間被循環緩衝覆寫；前景編碼檔名也按 session 區分，避免快速返回覆寫舊資料。
- 即時與相簿共用 FIT 取景、暫停、時間軸、前後 5 秒、0.25×／0.5×／1×與鏡像。鏡像只翻影片，速度沿用既有設定。
- 暖機標示直到第一個延遲畫格實際呈現才顯示已就緒；倒數已歸零但未有畫格時顯示等待，保存按鈕也同步禁用。
- 手動介入回放後停留供研究；未介入的回放播完仍可返回。開啟保存結果會清除即時回放路由。
- 一次保持手勢只觸發一次；相簿與設定停用手勢。保留手動備援、螢幕常亮和系統安全區。
- 相簿播放頁「分享影片」用既有系統分享；刪除增加確認，成功才離開。

## 媒體與狀態所有權

RecordingLifecycle 防重複與尾段等待；ContinuousRecordingSession 只負責串接及完成檔案。ViewModel 管理錄影和單箭各自的 SaveStatus。

SegmentTimeline 將每一輸入片段的首個 PTS 正規化，再銜接至下一個影格時間；PlaybackTimeline 將播放清單相對位置轉成單一可拖曳時間軸。

ClipSnapshot 保護即時回放與匯出讀取；回放、匯出和重試都不再使用該副本時才清理。前景片段在編碼器結束、錄影片段處理與單箭工作完成後清理。

## 安裝方式

此 APK 使用 `com.archery.delaycam.delayfix3`，名稱「射箭 Delay Camera 修正版 3」，可與修正版 2 並存。先前 GitHub runner 的臨時 debug 私鑰沒有保留，因此這次不宣稱可直接覆蓋安裝舊測試版。

舊版 App 和已保存的影片保留。新版內建相簿主要顯示它自己建立的影片，舊影片可繼續從舊版或系統相簿觀看。本版是 debug 測試 APK，未建立正式發佈簽章流程。CI 明確指定 DELAYCAM_DEBUG_KEYSTORE，並在同一分支快取該測試私鑰；GitHub 快取有分支範圍及淘汰機制，不能當作正式金鑰備份。跨分支持續發佈前，應由擁有者將固定簽章私鑰備份並存入受保護的 CI secrets。

## 驗證

JVM：既有 ForegroundCaptureSession，以及新的錄影狀態、片段 PTS、跨片段時間軸、手勢保持／重新啟用測試。

Android 模擬器：錄影文字操作、忙碌防重複、保存完成才提供查看、失敗重試、直接切播放速度／鏡像／暫停；另以一秒 H.264 測試素材驗證兩段串接後可解碼、時間戳遞增、完整錄影完成、快照抗覆寫與失敗清除待保存項目。

建置驗證：來源 commit `2983b0666918b517d686f291eb3be7ab11ae92e7`，Actions [38162424871](https://github.com/patty60723/delayCamForArchery/actions/runs/38162424871)。build 與 ui-tests 均成功；testDebugUnitTest 15 項，connectedDebugAndroidTest 8 項，失敗／錯誤／略過均為 0。assembleDebug 與 assembleDebugAndroidTest 成功；APK v2 簽章驗證通過，debug 金鑰快取保存成功。DESIGN.md lint 0 errors，6 個文件 token 引用提示，不代表 Android 執行錯誤。

- [修正版 3 APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38162424871/artifacts/11710397939)：解壓後安裝 app-debug.apk，不需要 Android Studio。Actions 保留至 2026-10-25；之後可重新建置。
- APK SHA-256：`a4b47ace251f796228d21101496da361b0f7c0b28e85fb5bb898b9917d6ca04b`。
- 簽章憑證 SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`。

模擬器為 Android API 34、x86_64、Pixel 6 設定。8 項測試包含 4 個 Compose 控制操作與 4 個原生媒體匯出；不是完整 Pixel 實機相機測試。CI 留存錄影與播放控制的構件截圖，已檢查主要控制與成功狀態；不宣稱已驗證戶外或完整頁面無障礙。

實際 Pixel 的 30 分鐘延遲觀看並錄影、進出桌面、尾段可播放、戶外可讀與完整 TalkBack／大字／轉向測試仍需實機驗收。逐格與 A-B 循環等延伸功能也尚未實作。

靜態設計稽核使用 frontend-design-premium 的 audit_project.py，嚴格模式無契約錯誤；工具的語法掃描以網頁為主，不能代替 Compose 編譯、Android 操作測試或無障礙實測。

