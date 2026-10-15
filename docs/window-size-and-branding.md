# 0.2.6 箭影：有效畫面放大、即時位置與測試版識別

## 使用者問題

手機截圖顯示延遲影片只有中央一小塊，即時影像也很小。0.2.5 保留編碼器尺寸後，橫向影像先縮進直向編碼畫布，播放器又把含黑邊的整張畫布 FIT 到橫向視窗，造成二次縮小；即時 PreviewView 也固定 3:4 且宽度上限 96 dp。

## 修正

- 編碼仍保持單一 MediaCodec；以 Surface 提交的單調時間與輸出 PTS 對應實際影像比例。每段只有全部樣本比例相同時，才記錄有效影像比例。播放時跟隨該 MediaItem，等比放大到有效影像碰到視窗寬或高，移除多餘的內部黑邊，不裁人、不拉伸。跨旋轉的混合片段安全顯示完整畫布，最多等待下一個比例穩定的片段；不拿現在手機方向猜延遲影片。
- 即時小窗放大，依實際相機比例配置；維持同一 PreviewView，不重綁相機。
- 「設定 → 即時視窗位置 → 左下／右下」，立即生效並保存。舊慣用手位置資料遷移為相應左右；沒有資料預設右下。保存提示移到另一側。
- 名稱縮短為「箭影」，測試版「測試9·箭影」把測試放在最前面。圖示為橙色箭與回放環，測試包加綠色 TEST 徽章。設定頁顯示完整版本號與 build code。

## 安裝

版本 0.2.6-fast-return-test，versionCode 9；套件仍 com.archery.delaycam.fastresume，保持原 CI 簽章，覆蓋既有快速返回測試版，不須解除安裝。名稱與圖示變更不代表另裝一份，也不影響另一個修正版 3。PR #6 持續草稿，尚不切換正式版。

## 驗證與限制

JVM 覆蓋跨方向有效畫面尺寸、不得裁切與未知比例回退、延後編碼 PTS 仍配對舊方向。原生測試涵蓋真正編碼輸出的比例、帶黑邊影片的黃／青上下邊緣完整填到可用高度、左右設定的選取／保存／實際位置、放大的即時小窗、旋轉仍持續播放及錄影、短名稱與實際 adaptive icon 截圖。保留既有暖機秒數與返回桌面的安全重置。

原有影片與對外匯出的錄影保留固定編碼尺寸，本輪放大主要作用於延遲觀看；不宣稱提升影像來源解析度或重製舊影片。真人射箭姿勢、Pixel 長時間溫度與相機硬體仍待實測。

## 已完成的驗證與交付（2026-10-14）

- [最終 CI](https://github.com/patty60723/delayCamForArchery/actions/runs/38510440424)：42 項 JVM + 30 項 API 34 原生測試 = 72 項通過，零失敗、零錯誤、零跳過；設計文件 lint 通過。
- 已測程式與資源 commit：`8e60cc6d442fe3bc2a28518d0a0cc8e7a9129a98`；後續只補文件與證據。
- 真正 MainActivity／CameraX／MediaCodec／Media3 整合驗證：旋轉保持 Activity、PreviewView、編碼器與緩衝世代，持續渲染畫格、錄影保存後尾段可解碼，背景返回仍建立新 session。左右位置按鈕、選取狀態、DataStore 保存、實際左下位置與放大小窗亦通過；此測試完成 40.553 秒。
- [原生報告及截圖 ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38510440424/artifacts/11869478708)。已目視確認有效影像的黃／青上下邊完整使用可用高度、旋轉後的大即時小窗，以及真正 Android adaptive icon 的 TEST 徽章。徽章固定 dp 定位曾被遮罩裁成細線，已改為 108×108 相對座標，並加入可見面積／高度／安全範圍斷言。
- `COEXISTENCE_INSTALL_OK`：兩種套件在模擬器同時安裝。最終包的名稱、版本與簽章檢查通過。
- [最終 APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38510440424/artifacts/11869737788)：解壓縮安裝 `app-debug.apk`。名稱「測試9·箭影」，版本 0.2.6-fast-return-test，versionCode 9，套件 com.archery.delaycam.fastresume。產物保留至 2026-10-28。
- APK SHA-256：`f00d8d1a57b940f451fbf66d60c029e219d926fcde6593455dc0bf0c65f3bad2`。
- 簽章 SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`；與既有測試版一致，可覆蓋更新，包括此前同 versionCode 9 的中間測試包。
- 原生環境：API 34、x86_64、Pixel 6 profile、emulated 前相機。Pixel 手機的感測器、真人手勢與長時間溫度仍需實機驗收；模擬器不宣稱已完成真人辨識驗收。


180° 旋轉補強：原生測試發現 Activity 的 View.display 可因 resource override 保留舊方向，且橫向 180° 不一定有公開組態變更。MainActivity 改用 application context 的 DisplayManager 讀取實體螢幕方向並在回呼後補一次更新，測試亦以同一實體螢幕方向驗證；不重建編碼器或清除緩衝。

方向通知時序補強：DisplayListener 可能在 Display cache 更新前先通知，180° 又沒有後續尺寸變更。因此前景期間每 250 ms 比對方向，只有方向不同才設定 targetRotation；退背景移除該工作。這不重綁相機、不重啟編碼器，並保留事件回呼的即時更新。
