# 多機22：切換 App 仍繼續拍攝與分享

## 使用者決策與操作

使用者指出「不能切到別的App不合理」，並於2026-10-19同意優先改為相機前景服務。這版取代多機19–21切背景即撤銷的政策：先在可見的箭影畫面開啟多裝置分享，等待網址出現後，即可切到LINE傳連結、其他App或桌面；拍攝、分享與已開始的整段錄影繼續，本次網址不變。

Android常駐通知標示「箭影正在拍攝與分享」，有觀看要求時顯示待確認數量，點擊回到同一多裝置頁核對。通知有「停止分享」。背景停止分享會結束拍攝並保存整段錄影；在箭影前景停止分享，只關閉遠端觀看，仍可繼續本機練習。

未開啟分享時，離開App仍依原規則停止拍攝與保存錄影。主動停止、滑掉App、系統結束服務／程序會讓舊連結失效，下次需要手動開啟；服務不自動重啟。相機被其他App使用、系統強制終止或OEM限制仍可能中斷；本輪不承諾在程序死亡後恢復原連結或錄影。

Android 13以上首次開始時可要求通知權限；拒絕不阻止合法的相機前景服務，但通知可能不在通知欄顯示，App會說明從連線頁或Android執行中的應用程式停止。沒有新增麥克風、定位或附近裝置權限。

## SA 與擁有者

| 資源／操作 | 擁有者 | 生命週期 |
| --- | --- | --- |
| 拍攝ViewModelStore | DelayCamApp | 可見／存在的Activity或使用者啟動的分享服務；最後離開時等保存完成再釋放 |
| CameraX bind／analyzer | CameraCaptureController | 可見Activity或已提升的相機前景服務維持STARTED；不綁Activity本身 |
| 畫面／背景Preview Surface | PreviewSurfaceRouter | 前景PreviewView；背景持續排空ImageReader；不用另一台相機或另一個編碼器 |
| HTTP、token與世代 | DelayCamViewModel／LanViewerServer | 沿用既有本次分享；切App不換，停止／服務失去時撤銷 |
| 前景服務、通知與CPU wake lock | ViewerForegroundService | 明確啟動至停止／onDestroy；START_NOT_STICKY，不由背景或開機自動啟動 |
| 本機延遲播放 | DelayedPlaybackController／DelayCamViewModel | 背景停止解碼並釋放播放lease，保留拍攝緩衝；回實際練習頁接回目前延遲 |

啟動必須發生在App可見且相機權限有效時：使用者開始 → busy／generation → startForegroundService → camera型別startForeground成功 → HTTP服務與網址發布。服務啟動有5秒界限；失敗、超時或舊generation回呼不能留下有效網址或孤兒服務。Android 14+宣告FOREGROUND_SERVICE_CAMERA；Android 10採一般FGS、Android 11+使用camera type。新增FOREGROUND_SERVICE、POST_NOTIFICATIONS及WAKE_LOCK權限均限此明確分享用途。

不能只保留HTTP socket：原相機綁Activity.onStop會停止出片段，因此本版把CameraX owner移到應用程式拍攝session。PreviewView的TextureView在背景或Activity銷毀時亦會失去Surface，改由PreviewSurfaceRouter提供可排空的ImageReader，避免Preview UseCase等待畫面Surface連帶卡住ImageAnalysis。CameraX、MediaCodec及拍攝session都維持同一個。

Activity重建時沿用應用程式拍攝模型，服務仍持有相機與網址。最後Activity不存在且沒有服務時，等單箭／整段保存完成後再清理CameraX、ViewModelStore、編碼器與播放器；wake lock與通知由服務停止／銷毀釋放。

## 返回與停止

背景時停用手勢與回放自動返回，避免在LINE操作時意外存檔或切換錄影；已開始的錄影仍接收同一編碼器的片段。本機隱藏播放器不留未播放佇列，避免長時間背景將所有MP4永久pin住。

返回練習以保留的來源時間與FastResumePlan接回現在減設定延遲的位置，不重新建立相機／編碼器／server。通知進多裝置頁時沒有影片Surface，不應以此當播放器失效並清掉緩衝；直到實際返回練習才接上本機播放。真實缺段、過期資料或解碼錯誤仍使用安全準備流程，不能承諾所有系統條件下零等待。

通知不含token、網址或對方IP。觀看批准仍只在App內核對代碼，沒有新增遠端控制路由。停止按鈕使用非exported服務與本次generation；舊通知不能用於開啟新相機或批准觀看。

## 版本與安裝

版本0.4.0-lan-viewer-test、versionCode22、啟動器「多機22·箭影」，package沿用com.archery.delaycam.lanviewer。

[下載多機22 APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/39239670279/artifacts/12181011662)，解壓安裝app-debug.apk；可直接覆蓋多機19／20／21，不需要解除安裝，單機測試18可保留。GitHub Actions產物保存至2026-11-02，下載可能需要登入GitHub。

- APK SHA-256：`5ac91732c9147ffeeb0a5e8e8e21bb4e4d03e4a5f2db990feccf47a2457401bf`
- 簽章SHA-256：`88a2f38df88584d3ec9bdd684afa80d23e37917ff68a360bd6f3e24dc6bc3247`，CI同時檢查既有簽章指紋與APK實際簽章。
- 測試來源：`cc1517393d90c22405a6636541c6f1ba5505ecbf`。

## 本輪驗證

[CI執行與產物](https://github.com/patty60723/delayCamForArchery/actions/runs/39239670279)。四組工作均成功：67個JVM單元測試、Android 14完整58個測試、Android 16背景分享3個測試，全部零失敗／錯誤／跳過；桌面Chrome的固定影片與Android實拍影片各18項檢查全通過。CI亦實際並裝單機測試18與多機22，確認兩個套件同時存在。

Android 16真實模擬器相機的3個新增整合測試已通過：切到Android設定App後新增HTTP片段並解碼MP4畫面；從實際通知返回同一觀看頁批准連線；返回練習維持來源緩衝世代；背景停止通知後保存可解碼錄影並撤銷舊網址。另驗證Activity重建保持相機／編碼器／server，以及在系統設定實際關閉通知頻道後，背景仍產生新影像，服務失去時舊連結失效。67個JVM單元測試亦已通過。

返回緩衝測試曾抓到正常完成的GOP超過5秒而被當作無效的問題：僅背景返回允許最長15秒片段，普通回放仍維持5秒；時鐘偏差、缺段、過期與來源世代檢查不變。尚未完成的目標片段保留緩衝並等新片段再接上，最長15秒才安全重新準備。通知返回也維持MainActivity原始launcher Intent，只消費一次導頁extra，避免生命週期辨識失效與重建時重複跳頁。

這些是Android 14／16模擬器與桌面Chrome驗證，不代替Pixel實機、iPhone瀏覽器及特定熱點的互通驗證。請實機測試：開啟分享等網址 → 切LINE傳連結 → 朋友連熱點開啟並配對 → 維持背景一分鐘確認持續更新 → 返回練習 → 通知停止。系統強制停止、更換網路、其他App佔用相機與熱點隔離仍需個別處理；這版解決的是正常切App使服務被主動關掉的問題。
