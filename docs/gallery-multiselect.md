# 畫廊長按多選（0.2.13 / 測試17）

一般點擊仍播放，長按或「選取」進入多選。可全選、清空、分享多段或確認後批次刪除。第一次返回取消選取；忙碌中返回可離頁而不中止已開始的刪除。URI 為選取與網格鍵，排序或新增影片不會改選別段。

分享預檢全部影片可讀，單段 ACTION_SEND、多段 ACTION_SEND_MULTIPLE，ClipData 包含每一個 URI 並僅授予暫時讀取權限。目的地由 Android 分享面板選擇，App 不自動送出。既有播放器共用相同流程。選取留在畫廊，從分享面板返回可繼續整理。

批次刪除逐項記錄成功與失敗；移除保存結果引用及 ClipPresentationStore metadata。Android 11+ 系統已執行刪除後驗證存在性，不重複刪除；每批授權上限 1000 URI。Android 10 RecoverableSecurityException 授權後重試原項目。取消授權停止後續提示，但先前已完成的刪除不回復。未成功項目保留選取，精確顯示結果。重新整理失敗保留清單，避免假裝畫廊為空；Movies/ArcheryBackup 不屬於畫廊範圍。

測試包含 URI 選取、刷新與全選 reducer，原生 Intent 的所有 URI 與權限，以及實際 MediaStore 影片的長按、取消確認、多選刪除、未選取保留和相鄰資料夾排除。CI 結果與 APK 連結於建置完成後補上。其他 App 所有權的系統權限對話框、Pixel 實際分享目的地與完整 TalkBack 路徑仍需實機驗收。
