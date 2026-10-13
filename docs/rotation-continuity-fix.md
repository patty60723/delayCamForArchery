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

2026-10-13 驗證完成：

- [完整 CI](https://github.com/patty60723/delayCamForArchery/actions/runs/38378150491)：38 項 JVM + 28 項原生 = 66 項通過，零失敗／零跳過；設計文件 lint 通過，strict UI audit 零 findings。
- 程式與測試 commit：`505f0e5260a5c8e1672976a8452c7f3b3654ab38`；後續僅補文件證據。
- API 34、x86_64、Pixel 6 模擬器、emulated 前相機。RotationContinuityTest 完成 38.369 秒；實際 CameraX、MediaCodec、Media3 與 MediaStore 流程全部通過。並核對 `COEXISTENCE_INSTALL_OK`，兩種套件同時存在。
- [原生測試報告與截圖 ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38378150491/artifacts/11803155583)。已目視檢查 `rotation-cold-landscape.png`（啟動後立即轉橫向）、`rotation-recording-landscape.png` 及直向既有版面：右下小窗可見，影片使用可用高度，停止錄影可操作。橫向錄影計時由 0:00 持續到 0:16；以送交渲染的畫格與保存後解碼斷言驗證動態，截圖本身不作為流暢度證明。
- [APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38378150491/artifacts/11802028669)：解壓縮後安裝 `app-debug.apk`；產物保留至 2026-10-27。
- APK SHA-256：`a863194bb65be88de60e596662b78afe3953e286b17a5faccc55c9f3fa4dfb0f`。
- 簽章 SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`，與原 0.2.4 測試 APK 一致；套件名稱、版本與 App 名稱核對通過。

實機需確認：橫向直接開啟及開啟後立即轉橫向，暖機後來回轉向、邊錄邊轉向、小窗收起再旋轉、回桌面再返回。模擬器不取代 Pixel 手勢與相機硬體實測。

## 安裝與分支

版本 0.2.5-fast-return-test，versionCode 8，套件 com.archery.delaycam.fastresume，名稱「射箭 Delay 快速返回測試」。沿用原測試版簽章，可覆蓋 0.2.4；原「修正版 3」仍可並存。變更保留於 PR #6 草稿，不合併正式分支。

測試環境修正：首次 CI 在啟動轉向的前置條件超時，App 的 fullSensor 不遵從 UiAutomation 使用者旋轉鎖定。測試改由 Activity.requestedOrientation 驅動實際組態變更，不改 App 的感測器旋轉政策；播放連續性使用實際送交渲染的畫格數驗證，避免在接段瞬間取樣 isPlaying。
