# 多機20：獨立多裝置頁

> 多機21已依使用者決定移除設定捷徑，並修正熱點位址篩選與舊連結失效提示。最新使用方式請見[多機21文件](lan-viewer-hotspot-fix.md)；本頁保留多機20的驗證紀錄。

[下載多機20 APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38771731653/artifacts/11992890046) · 解壓安裝 app-debug.apk。版本 0.3.1-lan-viewer-test，code20，啟動器名稱「多機20·箭影」。

## 使用方式

本版使用多機19相同的 package 與固定簽章，versionCode 升至20；可以直接更新多機19，不用移除，單機測試18仍可並存。連線仍需手機前景與同一 Wi-Fi／手機熱點。

- 橫向練習：操作 → 多裝置。
- 直向練習：標頭的多裝置。
- 設定：上方的多裝置觀看，連到同一頁。
- 多裝置頁：開啟電腦觀看 → 電腦輸入短位址 → 電腦提出要求 → 手機核對碼相同後允許 → 電腦開始觀看。
- 返回練習後，開啟中的連線以小型浮動提示顯示；新要求顯示待確認數量，點擊提示回到同一連線頁。
- QR Code、完整連結與其他網路位址預設收起；完整連結及QR含本次觀看權限，只供自己的裝置。

一般返回回到來源頁；返回練習直接回到練習，不需要再經過設定。這些內部切頁不停止連線或相機。明確關閉觀看連線及切桌面／背景仍撤銷本次連結，回 App 需重新開啟。尚未加入裝置心跳，因此已開啟代表觀看服務已開啟，不代表電腦已在線；不顯示虛構的連線台數。

## PM／UX 決策

依使用者確認的 review，連線與核對是每次練習的操作，不再與長期偏好混在同一個捲動設定表單。採單一專頁、兩個入口，不加設定分頁與底部導覽列。橫向影片不因提示或連線入口而縮小，提示不強制切頁、不播放聲音；操作面板展開時在入口顯示待確認數量。

手機頁保留既有黑底、橙色、繁中與 Material 元件；待確認卡片優先於短位址，短位址優先於次要連線方式。未開啟、啟動中、無網路位址、啟動失敗、等待觀看與待確認均有對應文字與下一步。

## SA 與生命週期

Routes.VIEWER → ViewerScreen → ViewerShareControls 是唯一連線控制路徑。ViewerShareStatus 僅呈現 ViewModel.viewerShare，與連線頁共享狀態，不另外管理 session。SettingsScreen 只導航，LivePracticeLayout 擁有浮動位置，DelayCamNavHost 仍只有一個 composition 位置與共用錄影控制。

此頁 setPracticeVisible=false，停用練習手勢；沒有 stopViewerShare、相機重綁、編碼器重建或緩衝清除。返回練習以 popBackStack(LIVE,false) 清除上層頁。錄影中仍可停止；此頁空閒時不提供開始／繼續錄影，沿用測試18的全域入口限制。

原有 HTTP 權限模型、單一相機、MP4片段傳輸、背景撤銷政策與已知片段交接停頓都維持。這次不新增遠端控制、永久記住裝置或WebRTC。

## 驗證

[CI 38771731653](https://github.com/patty60723/delayCamForArchery/actions/runs/38771731653) 全部三個 job 成功；來源 dffecfc23e50461f93a75897adb353c3f05cf951。後續文件提交不改 APK。

- 59 JVM + 55 Android API34 原生測試，共114項，0失敗／錯誤／跳過；已下載XML核對。
- LanViewerIntegrationTest 使用真正 MainActivity／CameraX，經練習入口開啟連線，返回練習後由實際HTTP要求觸發待確認提示，再以手機批准。切至設定、同一多裝置頁及返回仍保留同一相機 session、MediaCodec 與 server。旋轉保持連線；明確停止與背景撤銷；既有測試18實際APK並存通過。
- ViewerNavigationUiTest 驗證直向雙入口共用流程、啟動失敗可重試、忙碌禁用、無位址可重新取得、此頁無開始錄影、一般返回到來源頁；560×220dp受控橫向的開啟／待確認提示不縮影片、不自行切頁。
- 桌面 Chromium 對合成H.264及本輪實際Android拍攝片段，各通過18項批准、解碼、連續交接、延遲漂移、FIT／FILL、面板、焦點、斷線／過期／分頁返回檢查，無未處理JS錯誤。前一輪同產品程式碼的實際2926ms片段也已通過18項。
- DESIGN lint、瀏覽器共用色票／JS語法、strict premium audit 0 findings、APK套件／版本／固定簽章檢查均通過。
- 已檢視未開啟、已開啟、核對批准、練習頁待確認、設定入口及直向連線頁共六張原生截圖。拍攝樣本是模擬器合成影像，不代表Pixel實際畫質。

[原生報告／截圖／實拍片段與瀏覽器結果](https://github.com/patty60723/delayCamForArchery/actions/runs/38771731653/artifacts/11991284814) · [合成影片瀏覽器結果](https://github.com/patty60723/delayCamForArchery/actions/runs/38771731653/artifacts/11991502321) · [單元測試XML](https://github.com/patty60723/delayCamForArchery/actions/runs/38771731653/artifacts/11992830171)

這些驗證不代替Pixel→電腦實際Wi-Fi／熱點測試；Safari與完整TalkBack尚未驗收。請優先實測已回到練習時電腦才要求觀看、批准後返回是否維持延遲，以及橫直切換是否持續連線。

APK SHA-256：974ed724eda0657c831f38d1a49597ed7710e9dad74ab4d34aed288b38dcefba。
固定簽章 SHA-256：88a2f38df88584d3ec9bdd684afa80d23e37917ff68a360bd6f3e24dc6bc3247。
