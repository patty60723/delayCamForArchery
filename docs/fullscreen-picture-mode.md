# 0.2.8 測試11·箭影：可選完整畫面與滿版

使用者要求攝影畫面可像原生相機一樣填滿視窗，並保留現有完整比例。設定 → 攝影畫面顯示可選「完整畫面」或「滿版」，DataStore 記住選擇。預設完整畫面，升級不默默裁切。

主延遲、單箭回放、已保存播放器共用 VideoPlayer。先依播放片段 metadata 取得有效影像矩形，再 FIT 用最小縮放比／FILL 用最大縮放比；雙軸使用同倍率，父層 clipToBounds 裁切邊緣，不拉伸。舊影片缺少比例時沿用完整編碼畫布，原檔內黑邊可能仍存在。即時小窗保持完整構圖。

只是顯示模式，CameraX、編碼器、匯出和原始影片不變。切換不 reset、不清空延遲緩衝、不重建播放器。原生相機有不同鏡頭、感光元件與處理策略；本功能提供滿版觀看，不承諾完全相同的視角或影像品質。

版本 code 11，0.2.8-fast-return-test，名稱測試11·箭影；套件與簽章沿用前版，可覆蓋更新。43 項 JVM＋32 項 Android 原生測試，共 75 項已通過；0 failures、0 errors、0 skipped。

下一項「熱點＋瀏覽器多裝置觀看」已開始實作，保留於[獨立原型分支](https://github.com/patty60723/delayCamForArchery/tree/codex/lan-viewer-prototype)。本次 APK 先交付滿版設定；原型需完成 HTTP／瀏覽器播放及三平台相容性驗證再提供，尚未將多裝置標記完成。

## 最終驗證與下載

- 來源 commit：`cbccd906472567226924c7e1e70fd60f95fd2804`。
- [CI 38602261350](https://github.com/patty60723/delayCamForArchery/actions/runs/38602261350) 的 build／ui-tests 均成功，確認與正式版並存。
- [APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38602261350/artifacts/11914845989)，解壓縮安裝 app-debug.apk；可覆蓋測試10·箭影，artifact 到期 2026-10-28。
- APK SHA-256：`4697d8b541cd9bafdfb1b9beb176f043176758ebd86d498c962313cc04fe9075`。
- 簽章 SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`，與前版一致。
- [原生報告與截圖](https://github.com/patty60723/delayCamForArchery/actions/runs/38602261350/artifacts/11914511235) 含 replay-picture-fit.png／replay-picture-fill.png。ReplayReviewDeleteTest 等設定寫入與 UI 同步後，用實際畫面像素確認 FIT 邊緣有黑邊、FILL 同位置是有效綠色影像；不只檢查模式標籤。已檢視對照截圖。
- 原有回放保存／刪除、相機旋轉／前背景／錄影測試通過；Pixel 長時間與真人手勢仍需實機確認。PR #6 維持草稿，未合併正式版。

下一功能原型已在獨立分支編譯成功，含 HTTP／Range／過期片段檢查的 45 項 JVM 測試通過。原型分支的簽章核對未通過，沒有發布原型 APK；正式提供前需沿用受控簽章流程，並完成瀏覽器與三平台媒體測試。滿版 APK 沿用既有正確簽章，不受影響。
