# 射箭 Delay Camera — Interactive Prototype

一個可在瀏覽器中直接執行、驗證核心體驗的互動式 prototype,對應產品規格書
「射箭 Delay Camera App — 產品規格書」(第 1–8 節)。目的是在投入 Android 原生
開發前,先讓射手/利害關係人實際點得到、看得到「延遲鏡子 + 手勢/按鈕存檔 +
慢動作回放 + 設定 + 畫廊」這條核心流程,不是最終的 Android 產品。

## 執行方式

需要以 HTTP(S) 伺服器開啟(camera API 與 ES module 在 `file://` 下無法正常運作),
在專案根目錄執行任一種靜態伺服器,例如:

```bash
python3 -m http.server 8000
# 或
npx serve .
```

再以 **Chrome / Edge**(需要相機權限,建議 desktop 或 Android 版)開啟
`http://localhost:8000/`。手機瀏覽器需以 HTTPS 或 `localhost` 存取才會被允許使用相機。

## 對應規格 vs. 本 prototype 的取捨

這是純前端(HTML/Canvas/IndexedDB)prototype,沒有 Android 原生元件,因此以下
幾點刻意做了簡化,皆已在程式碼註解中標明:

| 規格項目 | Android 正式版做法(規格書) | 本 prototype 做法 |
| --- | --- | --- |
| 常駐延遲畫面 (2.1) | 60fps 短片段檔案循環覆寫 + 串接 | 相機畫面持續取樣進記憶體 ring buffer(~15fps、384×216),畫面 canvas 直接畫出「N 秒前」的那一張 |
| 回溯存檔 (2.2 步驟1) | 串接 buffer 片段輸出 MP4 (MediaCodec/MediaMuxer) | 立即從記憶體 buffer 播放慢動作回放(不需等待編碼);背景同時用 `canvas.captureStream()+MediaRecorder` 產生 WebM 存進畫廊 |
| 手勢辨識 (2.2 / 5.3) | MediaPipe Hands,固定角落 ROI,自訂「五指張開」判斷 | MediaPipe **GestureRecognizer**(Web/WASM 版)的內建 `Open_Palm` 類別做為「張開手掌」的替代判斷,並加入 9.1 提到的 0.35 秒穩定觸發門檻與 3 秒冷卻。**改為偵測全畫面而非固定角落 ROI**(實測角落 ROI 太小、手勢很難準確落在裡面比出來,全畫面辨識容錯高很多)。WASM 與模型從 Google 官方 CDN 於執行時下載;離線或網路受限時會自動降級為「僅手動按鈕」,不會讓 App 壞掉(這也正是規格 3.1 所說手勢辨識失敗時的備援設計) |
| 手動存檔按鈕 (3.1) | 有 | 有,行為與手勢觸發共用同一條存檔/回放流程 |
| 回放期間忽略新觸發 (2.2) | 有 | 有,以 `appState` 狀態機(LIVE/SAVING/REPLAY)保證 |
| 即時小視窗 (新增,非規格原有項目) | 無 | 主畫面左上/右上角疊加一個小的即時(無延遲)相機畫面,方便使用者確認當下鏡頭真的有拍到自己,不用等延遲畫面才看到 |
| 慣用手切換 (3.3/4) | 切換偵測區左右 | 手勢改為全畫面偵測後,這個設定改為切換**即時小視窗**顯示在左上或右上角,盡量避開比手勢的那隻手 |
| 儲存 (6) | MediaStore 永久資料夾 | IndexedDB(瀏覽器沙盒儲存,無法真的存進手機相簿;下載/分享用瀏覽器下載連結代替) |
| App 背景/中斷行為 (9.1 未定事項) | 未定 | 本 prototype 選擇的具體策略:切到背景時暫停取樣與辨識,回到前景後清空 buffer、重新累積(畫面會重新出現「準備延遲畫面…」倒數) |
| 儲存失敗提示 (9.2) | 未定 | 已補上:編碼/存檔失敗會跳出 toast 提示,不會讓使用者誤以為已存檔 |

## 檔案結構

```
index.html            四個畫面(Live / Replay / Settings / Gallery / Player)的 DOM 結構,手機外框樣式
style.css             所有畫面樣式
src/state.js          設定預設值、App 狀態與畫面列舉、極簡 pub/sub store
src/frameBuffer.js     持續取樣相機畫面的 ring buffer(對應 2.1 / 5.2)
src/delayRenderer.js   畫出「N 秒前」畫面 + 參考線(對應 2.1 / 3.1)
src/gestureEngine.js   MediaPipe GestureRecognizer 包裝(全畫面偵測)+ 穩定觸發門檻 + 手動備援(對應 2.2 / 5.3 / 9.1)
src/clipRecorder.js    立即慢動作回放 + 背景輸出 WebM(對應 2.2 / 3.2)
src/galleryStore.js    IndexedDB 存取(對應 3.4 / 6)
src/settingsStore.js   設定持久化(localStorage)
src/utils.js           檔名格式化等小工具
src/app.js             畫面導覽、狀態機、把上述模組串起來的主程式
```

## 已驗證的操作流程(以 Chromium headless + fake camera device 測試過)

- 開啟後顯示「準備延遲畫面…」倒數,buffer 累積足夠後自動切成延遲鏡子畫面。
- 手動存檔按鈕 → 立即切到慢動作回放畫面、可提早按「立即返回」、播放結束自動回到
  Live 畫面且回放期間按鈕/手勢會被忽略。
- 背景輸出的片段確實存進畫廊(縮圖、檔名、時長皆正確),點擊可全螢幕播放、可切換
  慢動作播放、可下載或刪除。
- 設定頁調整「延遲秒數」「回溯存檔範圍」會立即反映在下一次存檔的片段長度上。
- 手勢辨識模型下載失敗(離線 / 網路政策阻擋)時會自動顯示「手勢辨識未啟用,請用
  手動存檔按鈕」,其餘功能不受影響。

手勢辨識本身(對著鏡頭比出張開手掌)需要真實鏡頭與網路存取 MediaPipe 模型,建議在
一般開發機或手機瀏覽器上實測。

## 尚未涵蓋(刻意排除,見規格第 8 節「未來擴充」)

教練模式/多裝置連線、姿勢骨架疊加與角度量測、靶面自動計分、iOS 版本 — 這些都是
v1 之後的擴充項目,不在本 prototype 範圍內。
