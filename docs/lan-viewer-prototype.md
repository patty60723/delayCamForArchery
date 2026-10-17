# 多機19：Pixel 拍攝，電腦瀏覽器觀看

> 最新多機21支援獨立多裝置頁、待確認提示、熱點位址篩選與舊連結失效提示，可直接更新多機19／20。新版安裝與流程請見[多機21文件](lan-viewer-hotspot-fix.md)；下方保留多機19的驗證紀錄。

多機19已建置並通過下列自動驗證，接下來可測實際 Pixel→電腦的 Wi-Fi／熱點。以使用者確認可用的測試18為基礎整合，使用獨立 package，可保留測試18輪流開啟比較。

[下載 APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38751387321/artifacts/11981892078) · [原生報告、實際片段與瀏覽器截圖](https://github.com/patty60723/delayCamForArchery/actions/runs/38751387321/artifacts/11981877970) · [合成影片瀏覽器報告](https://github.com/patty60723/delayCamForArchery/actions/runs/38751387321/artifacts/11980548496)

## 安裝與連線

1. ZIP 解壓後，Pixel 安裝 app-debug.apk，名稱「多機19·箭影」，首次允許相機。測試18不用解除安裝；兩版設定各自保存，新版預設延遲5秒，可手動調成相同值比較。手機一次開一個拍攝 App。
2. 手機與電腦連同一個自己的 Wi-Fi／路由器；電腦接同一路由器的網路線也可以。另一種方式是在 Pixel 的系統設定開啟手機熱點，讓電腦連上。App 不代開熱點，不需要 Bluetooth；網頁資產與影片均在本機，不依賴外網或雲端帳號。
3. 多機19的「設定」往下捲到「電腦觀看 · 測試版」，按「開啟電腦觀看」。手機先停留在此頁。
4. 電腦 Chrome／Edge 輸入手機顯示的短位址，例如 http://192.168.x.x:連接埠/，按「向手機要求觀看」。核對兩邊代碼相同，在手機按「允許觀看」。不必切到其他手機 App 傳長連結，避免背景撤銷。
5. 電腦按「開始觀看」，手機返回練習頁並保持前景。電腦可展開「操作」，切換完整畫面／滿版或接回目前延遲，操作面板不縮小影片。
6. 結束按手機「關閉觀看連線」。手機切桌面會依原政策停止並保存錄影，也會關閉觀看連線；回 App 後需重新開啟，使用新位址再確認。這是本次連線確認，尚未永久記住電腦。

若顯示數個位址，選與電腦相同網路的位址；沒有位址時，確認 Wi-Fi／熱點後按「重新取得連結」。公共 Wi-Fi 可能隔離裝置，先改用自己的手機熱點。請使用畫面上的 http:// 位址，不設定路由器外網轉發。本原型使用本機 HTTP 明文連線，限自己的網路；完整觀看連結及QR含本次觀看權限，只供自己的裝置。

## 這輪的範圍

可測：Pixel 本機延遲／錄影／回放及畫廊多選、電腦延遲觀看、來源延遲設定、FIT／FILL、分頁返回、斷線接回、旋轉保持分享、停止或背景撤銷。

尚未加入永久配對／快速尋回裝置、遠端保存／錄影、雙角度、原檔收集或 WebRTC。手機回放單箭時，電腦繼續看延遲來源，不自動跟隨單箭回放；本輪網頁只提供完整／滿版與接回延遲。iPhone／iPad Safari尚未列為已驗證的平台。

本版傳遞完成的MP4片段，交接可能短暫停頓。保留最後已解碼畫面後換入新首畫格，斷線會明示最後畫面；不宣稱無縫串流或精確延遲。畫面顯示「設定延遲」與估計「目前影像約幾秒前」，傳輸與片段交接可能增加等待。

## 建議這次回報

- 電腦作業系統與 Chrome／Edge 版本、使用 Wi-Fi 或熱點。
- 短位址能否開啟，手機核對允許後能否開始播放。
- 設定5秒時等待是否大致合理，使用數分鐘後是否愈來愈慢。
- 片段交接有沒有黑一下／停一下；手機本機延遲、手勢與錄影是否正常。
- 電腦切分頁後返回、短暫斷網再連是否恢復。
- Pixel旋轉是否保持觀看；切桌面是否清楚斷線，重新開啟新位址能否觀看。

## SA 與實作

ViewerSegmentSource 只發布已完成片段的識別、來源時間、實際媒體長度、有效比例與epoch，不公開檔案路徑。HTTP每次讀取先 acquire ImmutableSegmentStore.Lease、讀完釋放；沿用測試18單一相機與編碼器，沒有額外編碼或JPEG壓縮。

LanViewerServer 提供本次192-bit隨機觀看token、靜態頁、status與segment，支援GET／HEAD／單一Range。短位址入口只處理觀看授權：電腦POST隨機192-bit要求ID，手機本機批准後才回傳觀看路徑。重送同ID冪等，最多4個待確認、24個短期項目，新要求清除3分鐘前項目；沒有遠端授權、保存或錄影路由。

DelayCamViewModel 依generation拒絕晚到的啟動／授權回呼。背景、停止及onCleared關閉socket與撤銷token；旋轉保持同一server、相機與編碼器。IP不視為永久身分，不保存配對資料。

viewer.js以拍攝端時鐘減delaySec選來源，不重複加延遲；限制累積漂移，落後過多接回現在的目標。載入新段時保持最後畫面，首畫格確實解碼才替換；斷線持續標示。分頁隱藏停止輪詢，回前景重查；epoch／delay變更重建觀看。join.js與join.html處理電腦要求、拒絕重試與批准後導向。

多機測試使用獨立簽章快取，公開憑證指紋固定於 docs/lan-viewer-signing-cert.sha256。CI復用同一快取並比較指紋，快取遺失後拒絕自動換簽章；不在repo或artifact公開private keystore。測試18的套件及憑證不變。

## 最終驗證

[CI 38751387321](https://github.com/patty60723/delayCamForArchery/actions/runs/38751387321)，來源 `b2c0eb2210c7ad7962b9cf8ff62177580265e527`：59 JVM＋53 Android API34原生測試，共112項，0失敗／錯誤／跳過。包含HTTP／Range／過期／路徑限制、本機批准前不回傳token、拒絕與重試、真實MainActivity／CameraX→HTTP MP4、手機批准、旋轉保持同一編碼器與server、停止／背景撤銷、與實際測試18 APK並存。既有相機來源、延遲播放、錄影、回放、畫廊多選及偏好持久化均通過。

桌面Chromium對1秒合成H.264影片與本輪3587ms實際Android片段，各通過18項檢查：未批准前無媒體、拒絕可重試、新要求ID、批准後觀看、首畫格解碼、連續交接、漂移限制、FIT／FILL、面板不縮影片、Escape與焦點、斷線／過期／新epoch恢復、隱藏停止輪詢及返回恢復、無未處理JS錯誤。實際片段測試目標5秒。另於本機Chromium以2236ms與4554ms實際片段驗證相同18項；結果JSON保存在本文件旁。

DESIGN lint、browser palette／JS語法、strict premium audit 0 findings、固定憑證與並存安裝均通過。已下載核對原生XML、settings截圖與瀏覽器影片畫面。GitHub Runner缺少CJK字型，CI網頁截圖中文字有缺字；本機Chromium已核對正常繁中顯示。這些不是Pixel→電腦無線實測、Safari或完整TalkBack驗收。

驗證期間曾修正：過期測試需等實際410及新畫格；獨立分支無法復用單機簽章快取；UTP卸載會清掉私有樣本目錄，改將合成相機樣本寫到CI的MediaStore Downloads；真實模擬器片段不固定1秒，瀏覽器測試改讀MP4 mvhd以實際長度安排時間與交接次數。沒有以取消斷言或只測metadata宣稱播放完成。

APK：0.3.0-lan-viewer-test，code19，com.archery.delaycam.lanviewer，「多機19·箭影」。SHA-256：`be875b056ef98ed7de00a790c7a0695e14f11b498ec07e6ea425210daead3c15`。憑證SHA-256：`88a2f38df88584d3ec9bdd684afa80d23e37917ff68a360bd6f3e24dc6bc3247`。後續提交僅更新docs；獨立draft PR，尚未合併。
