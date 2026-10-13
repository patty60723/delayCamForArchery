# 0.2.5 測試版：旋轉連續性與即時小窗

## 問題與原因

- 0.2.4 的 LiveScreen 為橫向保存獨立的 `false` 小窗預設值；開啟後轉橫向會像小窗消失。
- MainActivity 未處理螢幕方向組態，旋轉重建觸發 onStop → onAppBackgrounded → 清空緩衝、停止錄影，再由 onStart 重新暖機。

## 修正

- 小窗預設開啟，橫直共用 rememberSaveable 顯示設定，保留手動收起的選擇。
- Manifest 宣告 orientation、screenSize、smallestScreenSize 由 Activity 處理；Compose 正常接收新尺寸，不重建拍攝流程。
- 組態／DisplayManager 回呼更新 Preview 與 ImageAnalysis 的 targetRotation，包含不改變橫直配置的 180° 旋轉。保持 CameraX 綁定與 PreviewView。
- 一次前景拍攝維持同一 MediaCodec、相同輸出尺寸與 H.264 格式；輸入 Bitmap 轉向後，以 FIT 等比例置中並清黑底，避免裁切、殘影與重建編碼器造成的格式不一致。
- 真正回桌面、鎖屏仍停止／保存錄影，返回建立新緩衝；暖機秒數保留。

## 限制

影片輸出比例固定為這次啟動拍攝時的比例。中途旋轉可能有黑邊；不宣稱動態改變錄影解析度或旋轉後一定填滿螢幕。此輪以不中斷拍攝與完整保留身體為優先。系統終止程序及相機硬體錯誤仍可觸發重新暖機。

## 驗證

新增 RotationContinuityTest，使用真正 MainActivity、模擬器前鏡頭與編碼器，驗證啟動即轉橫向的小窗、橫直／180° 旋轉不重建 Activity、不更換編碼器、不清空緩衝、不重新暖機，手動小窗選擇保留；持續錄影並保存可解碼尾段；真正切背景仍建立新 session。FrameCanvasTest 驗證轉向後上下邊緣都存在、等比縮放、黑邊不殘留上一張像素。

CI 與 APK 證據完成後補上。實機需確認：橫向直接開啟及開啟後立即轉橫向，暖機後來回轉向、邊錄邊轉向、小窗收起再旋轉、回桌面再返回。模擬器不取代 Pixel 手勢與相機硬體實測。

## 安裝與分支

版本 0.2.5-fast-return-test，versionCode 8，套件 com.archery.delaycam.fastresume，名稱「射箭 Delay 快速返回測試」。沿用原測試版簽章，可覆蓋 0.2.4；原「修正版 3」仍可並存。變更保留於 PR #6 草稿，不合併正式分支。

測試環境修正：首次 CI 在啟動轉向的前置條件超時，App 的 fullSensor 不遵從 UiAutomation 使用者旋轉鎖定。測試改由 Activity.requestedOrientation 驅動實際組態變更，不改 App 的感測器旋轉政策；播放連續性使用實際送交渲染的畫格數驗證，避免在接段瞬間取樣 isPlaying。
