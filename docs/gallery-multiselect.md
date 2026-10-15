# 畫廊長按多選（0.2.13 / 測試17）

一般點擊仍播放，長按或「選取」進入多選。可全選、清空、分享多段或確認後批次刪除。第一次返回取消選取；忙碌中返回可離頁而不中止已開始的刪除。URI 為選取與網格鍵，排序或新增影片不會改選別段。

分享預檢全部影片可讀，單段 ACTION_SEND、多段 ACTION_SEND_MULTIPLE，ClipData 包含每一個 URI 並僅授予暫時讀取權限。目的地由 Android 分享面板選擇，App 不自動送出。既有播放器共用相同流程。選取留在畫廊，從分享面板返回可繼續整理。

批次刪除逐項記錄成功與失敗；移除保存結果引用及 ClipPresentationStore metadata。Android 11+ 系統已執行刪除後驗證存在性，不重複刪除；每批授權上限 1000 URI。Android 10 RecoverableSecurityException 授權後重試原項目。取消授權停止後續提示，但先前已完成的刪除不回復。未成功項目保留選取，精確顯示結果。重新整理失敗保留清單，避免假裝畫廊為空；Movies/ArcheryBackup 不屬於畫廊範圍。

測試包含 URI 選取、刷新與全選 reducer，原生 Intent 的所有 URI 與權限，以及實際 MediaStore 影片的長按、取消確認、多選刪除、未選取保留和相鄰資料夾排除。最終 CI 與 APK 見下方驗證紀錄。其他 App 所有權的系統權限對話框、Pixel 實際分享目的地與完整 TalkBack 路徑仍需實機驗收。

## 測試版安裝

版本：0.2.13-fast-return-test；versionCode：17；啟動器名稱：測試17·箭影；package：com.archery.delaycam.fastresume。

沿用既有測試版簽章，下載 ZIP 後解壓縮，開啟 app-debug.apk 覆蓋安裝測試16，不必解除安裝或使用 Android Studio。正式／舊版對照 App 與測試版仍使用不同 package，可並存。

建議裝機試用：長按一段影片，點第二段加入選取；取消刪除確認，核對兩段都還在；用分享開啟系統面板再返回，選取仍保留；最後只選可刪除的測試影片，確認刪除後其他影片仍在。全選再次點擊會清空選取，零選取時分享與刪除停用；按取消回一般模式。

選取由 ViewModel 維護，可跨目前 Activity 的旋轉與離頁保留；不承諾系統終止程序後保留未完成的選取與系統授權。批次刪除不是交易：中途取消或部分失敗，不回復已完成的刪除。


## 最終驗證與交付

來源：`da3148fec460a6e49a422f947911ecfb1cba4709`；[CI 38721227850](https://github.com/patty60723/delayCamForArchery/actions/runs/38721227850)。56 JVM + 50 Android API34 原生測試，共 106 項，0 失敗／錯誤／跳過。包含長按與第二段選取、刪除確認取消、實際 MediaStore 批次刪除、未選取與相鄰資料夾影片保留、零選取按鈕停用、重新整理成功清除舊錯誤、單段／多段 Intent 的 URI 與 READ 權限。既有回放刪除、設定持久化、相機來源、延遲黑畫格與旋轉錄影測試亦通過；並存安裝、DESIGN lint、strict premium audit 0 findings 通過。已下載核對原生 XML 與 gallery-multiselect.png。

初輪與已啟動的中間輪各有一項測試失敗：在 unmerged tree 上檢查按鈕內 Text 的停用語意；修正為定位含 ClickAction 的按鈕節點，保留停用斷言。刪除與分享的實際檢查原先已通過，最終完整重跑全部通過。另修正重新整理成功後清除舊讀取錯誤，加入對應驗證。

[下載 APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38721227850/artifacts/11967015290) · [原生報告與截圖](https://github.com/patty60723/delayCamForArchery/actions/runs/38721227850/artifacts/11967081381)

APK SHA-256：`bc58cc341330b735ae5682e2e4266f29d365949ab756bc50a3bb45fa0b25cd9f`。
簽章 SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`，與測試16一致。Artifacts 保存至 2026-10-29。後續提交僅補 docs，PR #6 保持 draft、尚未合併；多裝置原型不在本次變更。
