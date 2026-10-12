# 橫向延遲畫面放大（0.2.2）

## 原因與調整

使用者反映 0.2.1 的橫向 delay 視窗仍太小。原先影片雖可使用導航區的完整高度，但全域錄影列仍占至少 64 dp，固定右側操作欄占 180–240 dp；兩者一起限制實際影片顯示。

0.2.2 讓橫向 LIVE 的播放器占滿系統安全區內全部可用寬高。錄影控制浮在左上，右上「操作」展開可捲動的非模態面板，預設收起。展開只覆蓋畫面邊緣，播放器尺寸不變；收起或返回可關閉。錄影時間、停止按鈕、單箭與整段保存結果持續可見。橫向即時小窗預設收起，仍能手動打開。暖機轉圈與秒數完整保留。

播放器繼續使用 FIT：畫面放大不靠裁掉頭、腳或弓換取填滿；來源與螢幕比例不同時仍有黑邊。直向、回看、相簿與設定保持原本配置。

## 元件責任

- RecordingScreenLayout：決定共用錄影控制用頂部列或浮動版本，導航內容維持單一 composition 位置。
- RecordingControls：完整與 compact 共用錄影狀態、忙碌禁用、失敗重試及保存查看；compact 將結果放在第二列，避免兩個按鈕擠掉狀態。
- LivePracticeLayout：滿版影片、非模態操作面板、返回鍵收起與獨立保存回饋區。
- LiveScreen：依方向保存即時小窗開關，共用 LiveSaveActions、SaveStatusNotice 和 DelayWarmupIndicator。
- LiveCameraPreview：小窗收起時仍保留同一 attached PreviewView（1 dp、透明且不向輔助功能暴露），持續供應 CameraX 綁定的 Preview Surface，避免第一次橫向啟動因缺少 Surface 而停住擷取；開關小窗不重新綁定相機，SurfaceProvider 只在建立 View 時設定。

只調整介面配置；手勢、延遲來源、錄影與匯出生命週期沿用既有流程。DESIGN.md 與 UX-CONTRACT.md 已同步上述使用者要求的配置變更，未新增色彩或另一組狀態來源。

## 驗證與安裝

原生 UI 測試涵蓋橫向影片完整寬高、錄影停止、展開／收起不改變影片尺寸、短視窗捲動與返回、保存成功／失敗、忙碌禁用、1.5 倍字體、直向與暖機倒數。截圖中的影片區是明確標示的版面測試 fixture，不能當作實機 CameraX／編碼器驗收。

版本為 versionName 0.2.2、versionCode 5；測試套件 ID 仍為 com.archery.delaycam.delayfix3。CI 使用與 0.2.1 相同的快取簽章，並核對憑證 SHA-256 a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042。相同套件、正確憑證與更高 versionCode 才能直接覆蓋更新；APK 檔名不決定是否可更新。

## 已完成的驗證

已測試程式版本為 `227e2fbcdbe8e2024490ef897b1ad62d2fb43f25`。最後追加本文件的驗證紀錄，App 程式碼與 CI 配置不再變動。

| 項目 | 結果與範圍 |
| --- | --- |
| APK 建置與簽章 | 成功；憑證與 0.2.1 相同，支援覆蓋更新修正版 3 |
| JVM | 15 項通過，0 失敗／跳過 |
| 原生測試 | 18 項通過，0 失敗／跳過：9 項練習版面／暖機、4 項錄影與播放、4 項媒體匯出、1 項 CameraX 預覽 |
| CameraX 預覽 | 模擬前鏡頭，預設隱藏時收到畫格；顯示及再次收起後仍收到畫格，且 PreviewView 實例不變 |
| 設計文件 | CI DESIGN.md lint 通過；本機 strict audit 0 errors／warnings／violations，結果在 video-first-premium-audit.json |
| 截圖 | 已檢查滿版、面板展開、短橫向、1.5 倍字體、持續保存結果、直向與暖機狀態；影片區是版面 fixture |

CI 使用 API 34、Pixel 6 profile、x86_64 模擬器，明確設定 `-camera-front emulated`，不等同使用者的實際 Pixel。使用者手機的完整延遲編碼／錄影、日照可讀性與觀看距離仍需安裝後確認。

- [成功的 CI](https://github.com/patty60723/delayCamForArchery/actions/runs/38225481418)
- [下載 APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38225481418/artifacts/11736886563)，解壓縮後安裝 app-debug.apk；artifact 保留至 2026-10-26。
- [原生測試報告與截圖](https://github.com/patty60723/delayCamForArchery/actions/runs/38225481418/artifacts/11736886920)
- APK SHA-256：`3e4058c007be7148ea55002aff4b5f8d0d75b99212d5b5f5287edd5534555ae1`

## 手機驗收流程

1. 在上一版修正版 3 上直接安裝本版，確認版本更新與影片保留。
2. 手機橫放並開啟練習，等暖機秒數結束；確認有持續更新的延遲畫面。
3. 開始整段錄影，確認操作面板收起時仍看得到計時，並可直接停止。
4. 開啟／收起操作面板，確認影片比例與大小不變；再用系統返回確認先收起面板。
5. 從面板顯示／收起即時小窗，確認延遲畫面持續更新。
6. 只保存一箭，確認留在練習；停止整段後確認可查看已保存的影片。

截圖 fixture 驗證控制位置與可用性，CameraX 畫格測試驗證預覽 Surface 連接；手機流程才驗證真實延遲畫面與觀看效果。
