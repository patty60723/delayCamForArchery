# 多機21：熱點位址與失效連結提示

[下載多機21 APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/39012765059/artifacts/12093149763) · 解壓安裝 app-debug.apk，可直接更新多機19／20，不必解除安裝；單機測試18仍可保留。

版本0.3.2-lan-viewer-test，code21，com.archery.delaycam.lanviewer，啟動器名稱「多機21·箭影」。

## 回報與可確認的原因

使用者讓朋友的iPhone連上Pixel熱點後，瀏覽器顯示ERR_CONNECTION_FAILED；兩張圖的IP相同，但port分別36559與43431。使用者確認曾切到通訊App傳連結，再重新開啟觀看。

原設計在拍攝App背景時關閉觀看服務，每次手動重新開啟建立新的ServerSocket隨機port及192-bit token。因此傳出去的舊網址可能已失效。單憑10.144開頭不能判定它是行動網路位址；瀏覽器連頁面都未取得，也不能據此認定是iOS影片解碼問題。

程式碼另有確定的位址篩選漏洞：只用startsWith("rmnet")排除行動介面，漏掉v4-rmnet_data0等CLAT別名。這種介面的RFC1918位址可能被放到第一個觀看網址，卻無法供熱點使用者連入。新版本修正該漏洞，但沒有裝置網路清單，不能宣稱已證實截圖IP就是該介面。

## 本版調整

- 設定頁完全移除「多裝置觀看」捷徑及導航callback，只從練習頁「多裝置」進入；橫向位於「操作」選單。
- LanViewerNetwork讀取Android ConnectivityManager傳輸類型與NetworkInterface清單，排除已知行動／VPN介面及其位址。
- LanAddressPolicy補上rmnet、v4-rmnet、ccmni、pdp等行動介面及VPN別名篩選，優先熱點與已知LAN，保留合法RFC1918 IPv4，不把所有10/8一律當錯誤。
- 沒有LAN位址時顯示如何開啟Wi-Fi／熱點及重新取得，不回退使用行動網路網址。
- 切背景而關閉觀看後，連線頁保留「舊網址已失效」與重新開啟提示；再次啟動清除舊提示。
- 連線前明示先設定熱點，並請觀看者手動輸入最新短網址，避免再切到通訊App。

新增ACCESS_NETWORK_STATE普通權限供分類網路，安裝即授予，不要求定位、附近裝置或控制熱點的權限。不修改相機、編碼器、HTTP批准與播放權限，也沒有開啟背景分享。

## 兩種按鈕的差別

| 操作 | port／token | 對方如何操作 |
| --- | --- | --- |
| 重新取得連結 | 沿用目前server與token，只重新讀取網路位址 | 若網路位址改變，輸入新顯示的網址 |
| 關閉後再次開啟觀看 | 新server、新token，port可能不同 | 輸入本次新網址並重新核對批准 |
| App切到背景 | server關閉，本次連結撤銷 | 手機重新開啟觀看後，改用新網址 |
| App內返回練習／設定或旋轉 | 不關閉server／token | 可繼續觀看 |

## 熱點重測步驟

1. Pixel先開好手機熱點，朋友手機確認連到這個熱點。
2. Pixel打開多機21，在練習頁進入多裝置，再按開啟電腦觀看。拍攝App全程保持前景。
3. 朋友直接手動輸入Pixel畫面上完整http://短網址，不使用先前透過LINE取得的連結。
4. 朋友提出觀看要求；Pixel核對相同代碼後允許，朋友再按開始觀看。
5. Pixel可在App內返回練習；不要切桌面或其他App。若已切走，需重新開啟並使用新網址。

若新網址仍無法開啟，先確認Pixel顯示觀看連線已開啟、朋友未使用舊port；有多個位址時展開其他連線方式，選同一熱點網路的位址。iPhone可另用Safari測是否為瀏覽器限制，並確認系統若有詢問區域網路存取時已允許。頁面能開啟後，才進一步判斷批准或播放問題。Pixel熱點與iPhone WebKit仍需要此輪實機驗收。

## 驗證與交付

[CI 39012765059](https://github.com/patty60723/delayCamForArchery/actions/runs/39012765059) 全部三個job成功，來源7c0b81443ec7d6168146630d33e6c55ce12f4c7f。後續僅補充docs，不改APK。

- 64 JVM＋55 Android API34原生測試，共119項，0失敗／錯誤／跳過，已下載XML核對。
- 新增5項LanAddressPolicyTest：v4-rmnet與熱點同時存在、未知OEM行動／VPN傳輸分類、合法10/8與熱點排序、僅可用IPv4／去重／停用或loopback排除、無LAN不回退行動位址。
- ViewerNavigationUiTest核對設定不再有入口、練習入口仍可用、無位址與busy/error回復、背景後失效文字與重新開啟按鈕；560×220dp待確認提示維持滿版影片。
- LanViewerIntegrationTest由真正MainActivity／CameraX提供MP4，再以HTTP讀取並解碼；批准、切設定後回練習、refresh保持同一server與token、旋轉保持相機／編碼器、停止及背景撤銷與notice均通過；單機測試18實際APK仍可並存。
- 桌面Chromium對合成與本輪實際Android H.264片段，各18項播放／批准／漂移／重連檢查通過，沒有未處理JS錯誤。
- DESIGN lint、共用色票／JS語法、strict premium audit 0 findings、版本／套件／固定簽章核對通過；已檢視設定無入口、未開啟、批准與橫向待確認原生截圖。模擬器使用合成影像，不代表Pixel畫質。

[原生報告、截圖與實拍片段瀏覽器驗證](https://github.com/patty60723/delayCamForArchery/actions/runs/39012765059/artifacts/12093264828) · [單元測試XML](https://github.com/patty60723/delayCamForArchery/actions/runs/39012765059/artifacts/12093204536) · [合成影片瀏覽器驗證](https://github.com/patty60723/delayCamForArchery/actions/runs/39012765059/artifacts/12093301986)

這些驗證不等於已完成Pixel熱點→朋友iPhone實機測試，也不能證實截圖中的IP一定來自行動網路。iPhone連線與WebKit播放仍依上方步驟重測；手機背景時分享關閉的政策維持。

APK SHA-256：3fa747f66c5d2f4283b87fc920dda4d3c1c47e8b72b679c986789b7ac4f9c829。
固定憑證 SHA-256：88a2f38df88584d3ec9bdd684afa80d23e37917ff68a360bd6f3e24dc6bc3247。
