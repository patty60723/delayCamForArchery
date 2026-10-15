# 錄影入口依頁面限制（測試18 / 0.2.14）

問題：RecordingControls 位於全域導航外層，過去每個頁面都會呈現開始／繼續錄影，包括畫廊。

修正：DelayCamNavHost 僅在 Routes.LIVE 傳入 allowStart=true。RecordingControls 的非練習頁變體在 IDLE 且無保存狀態時不輸出錄影列，因此不占用額外頂部高度；有保存狀態則共用 SaveStatusNotice 提供進度、查看與失敗重試。錄影進行中仍顯示時間與停止按鈕，STARTING／STOPPING 維持忙碌禁用。瀏覽畫廊本身不會停止錄影，也不能從畫廊／設定／回放／已存播放器開始新錄影。

保留 RecordingScreenLayout 內唯一導航 composition 位置，不移動 NavHost，不重建相機、延遲播放器或緩衝。測試17的長按多選、批次刪除和分享沿用。

驗證：GalleryRecordingEntryTest 使用真實 DelayCamNavHost 從練習進畫廊、返回練習、再進設定，檢查開始／繼續入口的路由範圍；共用元件測試核對停止、忙碌禁用、保存進度／查看／重試。最終 CI 與 APK 如下。

## 安裝與驗收

版本 0.2.14-fast-return-test，versionCode 18，名稱「測試18·箭影」，package com.archery.delaycam.fastresume。沿用原簽章，可覆蓋測試17安裝，不需解除安裝。

裝機核對：未錄影時進畫廊，沒有「開始錄影」或空錄影列；返回練習可開始；已保存錄影後再進畫廊，顯示保存結果但沒有「繼續錄影」。錄影中進畫廊仍可停止，完成後能查看影片；失敗時可重試保存。多選刪除與分享沿用測試17。


## 驗證紀錄

來源 `5b3b54260388c6389ceb01a71ec82f078c6475f6`；[CI 38727110843](https://github.com/patty60723/delayCamForArchery/actions/runs/38727110843)：56 JVM＋52 Android API34 原生測試，共108項，0失敗／錯誤／跳過。包含真實導航入口範圍、共用元件停止與保存狀態，以及既有多選刪除／分享、相機來源、旋轉錄影、延遲播放與偏好持久化。DESIGN lint、strict premium audit 0 findings、固定原簽章與雙 package 並存安裝通過。

已下載核對原生 XML 與 gallery-no-recording-entry.png。導航測試以受控保存結果驗證「繼續錄影」只在練習頁，共用元件以受控錄影狀態驗證停止及忙碌；不宣稱這兩項等同 Pixel 上完整錄影的實機驗收。既有真實編碼與旋轉測試本輪亦通過。

[APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38727110843/artifacts/11968990401) · [原生報告及截圖](https://github.com/patty60723/delayCamForArchery/actions/runs/38727110843/artifacts/11968996486)

APK SHA-256：`e711b7a414a7a1d63bb3d1a8cf796c8b11564558f53ab5c0b10f1642ab87bd62`。簽章 SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`。後續提交僅補 docs；PR #6 維持 draft，尚未合併。
