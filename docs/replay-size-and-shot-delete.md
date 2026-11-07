# 0.2.7 測試10·箭影：回放放大與單箭刪除

使用者截圖是回放頁。0.2.6 只放大延遲 LIVE；回放缺少有效比例 metadata，還有固定側欄及多列頂部控制，造成影片二次縮小。刪除問題由使用者確認為回放頁缺按鈕。

本版將回放／已保存播放改為橫向完整安全區影片、可收起浮動操作與始終可按的返回；全程錄影控制亦在橫向改為浮動。私有單箭回放透過 SegmentInfo.playbackItem 帶入有效影像比例。新單箭匯出記錄精確匯出時間與比例於 App 私有 metadata，從相簿觀看也能依影格位置放大；混合／未知比例及舊影片則完整 FIT。沒有改動單一編碼器與緩衝連續性。

回放保存結果旁新增「刪除單箭」。App 先確認，保留不刪；確認後進入忙碌，真正刪除並核對結果才清掉保存狀態、更新相簿、返回練習。失敗保持畫面顯示錯誤，可重試。Android 30+ 如要求授權，使用 MediaStore.createDeleteRequest 系統確認；Android 29 使用 RecoverableSecurityException 的授權並重試。取消系統確認保留影片。

版本 0.2.7-fast-return-test，code 10，名稱「測試10·箭影」。沿用 com.archery.delaycam.fastresume 及既有簽章，直接覆蓋；TEST 圖示維持。PR #6 保留草稿。

新增真正 MediaStore 存檔→私有回放→取消／刪除測試、影片滿版與面板不改尺寸測試、匯出比例時間／混合片段／刪除清理測試。真人手勢與 Pixel 長時間效能仍需實測，跨 App 系統刪除授權需在實機確認。

Android 媒體核對補強：刪掉影片後，直接查詢原 item URI 可能收到 SecurityException；改查同一集合的 ID 是否存在。測試清理已刪的 URI 時亦容許其已失效，保證 ViewModel／播放器釋放；新增非同步整合測試設定時間上限，避免阻擋後續測試。

## 驗證與下載

- 測試來源 commit：`c8c5aa648a3016431d23f93abf91b3ee745f6c40`。
- [GitHub Actions 38530353750](https://github.com/patty60723/delayCamForArchery/actions/runs/38530353750)：build 與 ui-tests 均成功。
- JVM 42 項、Android API 34 原生 32 項，共 74 項；0 failures、0 errors、0 skipped。
- 真正匯出與刪除整合測試通過；回放 UI 測試確認影片區填滿橫向安全區、展開面板不縮小影片、取消保留影片、確認刪除後返回練習及清除比例 metadata。
- 原有相機旋轉／背景／錄影測試通過；模擬器已確認與正式版並存安裝。
- [APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38530353750/artifacts/11877691711)：解壓縮後安裝 `app-debug.apk`，可覆蓋測試9·箭影；artifact 到期 2026-10-28。
- APK SHA-256：`3f681a56b24138560177d562b8de76f0e132c7323cde2861c8ef99838b4c1c5e`。
- 簽章 SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`，與前版一致。
- [原生報告與截圖 ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38530353750/artifacts/11878565701)，截圖 `ui-screenshots/ArcheryUiReview/replay-fullscreen-delete-shot.png` 使用可辨識上下邊界的合成影片，驗證有效畫面已放大及刪除入口可見。

舊影片未記錄有效畫面比例，仍以完整編碼畫面播放；本版新存的單箭會帶入比例。新 UI 不改變舊影片內容。PR #6 維持 draft，待手機確認後再合併。
