# 錄影入口依頁面限制（測試18 / 0.2.14）

問題：RecordingControls 位於全域導航外層，過去每個頁面都會呈現開始／繼續錄影，包括畫廊。

修正：DelayCamNavHost 僅在 Routes.LIVE 傳入 allowStart=true。RecordingControls 的非練習頁變體在 IDLE 且無保存狀態時不輸出錄影列，因此不占用額外頂部高度；有保存狀態則共用 SaveStatusNotice 提供進度、查看與失敗重試。錄影進行中仍顯示時間與停止按鈕，STARTING／STOPPING 維持忙碌禁用。瀏覽畫廊本身不會停止錄影，也不能從畫廊／設定／回放／已存播放器開始新錄影。

保留 RecordingScreenLayout 內唯一導航 composition 位置，不移動 NavHost，不重建相機、延遲播放器或緩衝。測試17的長按多選、批次刪除和分享沿用。

驗證：GalleryRecordingEntryTest 使用真實 DelayCamNavHost 從練習進畫廊、返回練習、再進設定，檢查開始／繼續入口的路由範圍；共用元件測試核對停止、忙碌禁用、保存進度／查看／重試。最終 CI 與 APK 待完成後補上。
