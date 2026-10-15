# 測試15（0.2.11）：延遲畫面黑閃

## 回報與證據

使用者確認手機不移動仍會短暫黑一下，僅延遲大畫面發生，即時小窗正常；上傳影片會讓遠端介面重啟，因此不以影片上傳作為修復前提。尚無手機logcat，不能宣稱已確認實機唯一根因。

測試14原生RotationContinuityTest的既有紀錄顯示：06:34:19.732播放器進入STATE_ENDED，06:34:20.286下一段才開始BUFFERING，06:34:21.018回到READY。下一段檔案已於06:34:15.256完成，卻因逐段endedAt＋delay的timer而延後加入；播放器錯失預載機會。這是已可從紀錄核對的供片空窗，與黑閃現象相符，無法直接等同手機上的完整因果。

## 修正

DelayedPlaybackController保留原有首次開始時間（首段結束＋設定延遲）。完成片段立即入列並取得immutable lease，首個timer之前維持IDLE、不prepare、不顯示提早畫格。首次開始後Media3可預載下一段並按媒體時間軸接續，移除逐段的延後加入。已播片段依原策略裁剪，釋放對應lease並移除去重紀錄；reset取消timer與所有reader，真正來源缺段仍保留ENDED接回。快速返回維持now－delay的來源定位，不加新的暖機或編碼器。

另撤回測試14的高解析Bitmap立即recycle：unlockCanvasAndPost是硬體繪製提交，不代表GPU已讀完原像素。改讓圖形原生引用正常回收；手勢仍持有獨立的640長邊副本。此為生命週期防護，不宣稱已從手機證明它造成黑閃。維持720p目標、RGBA、單一固定畫布編碼器、4:3／16:9、觀看完整／滿版及原保存格式。

## 驗證與限制

新增真正Media3／TextureView原生測試：先將8個已完成來源入列，確認初始延遲前維持IDLE、第一畫格不提早；跨至少5個實際period轉換抽樣可見texture中心像素與PlayerView shutter，檢查黑畫格、重置遮罩及提早ENDED。同時驗證重複入列、檔案lease保護與裁剪釋放；另測真正佇列耗盡後新來源可恢復。這項預載斷言在舊逐段timer結構下無法成立；未另外對舊版執行CI，不宣稱已做實機A/B。

既有720p編碼／解碼細節、橫直不中斷、錄影保存、回放刪除及背景生命週期持續回歸。[CI 38707980442](https://github.com/patty60723/delayCamForArchery/actions/runs/38707980442)，來源commit `cae20426d3d00e09e5c1753972fc457e6f8415ea`、tree `e86590f1b5a3acbda4152d24ede348364ebc09d9`：47 JVM＋45 Android API34原生測試，共92項，0失敗／錯誤／跳過。build及ui-tests均success；DESIGN lint、固定簽章、套件標籤與正式／測試版並存安裝檢查通過。新增連續播放測試確認至少5次跨段、至少15次可見畫格抽樣，黑色texture、可見shutter與提早ENDED均為0；缺段耗盡後恢復亦通過。這些是測試斷言所驗證的下限，不臆測實際抽樣次數。

第一次[CI 38706616221](https://github.com/patty60723/delayCamForArchery/actions/runs/38706616221)的45原生測試中44通過，唯一失敗是新增缺段恢復測試在測試執行緒直接讀Media3狀態（IllegalStateException）。已將該兩個predicate改為在播放器application thread讀取；沒有刪除或放寬斷言。第二輪是相同App程式、修正測試讀取後完整重跑，非只重跑成功項目。

[APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38707980442/artifacts/11960175272)（解壓後安裝app-debug.apk） · [原生測試報告與截圖](https://github.com/patty60723/delayCamForArchery/actions/runs/38707980442/artifacts/11959354513)。下載artifact至2026-10-29有效。APK SHA-256：`bc7f95195772c29f0326c4e99325741faa5dde471b17980fcbbcb490c0db3c2d`；簽章憑證SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`。

工作環境在核對期間離線，最終測試修正及文件透過GitHub connector完成；驗證依GitHub Actions的完整build／native job紀錄與可下載原生報告，不宣稱已在本地解壓本輪報告或檢視本輪截圖。

模擬器通過只能驗證這些可觀察條件；Pixel硬體解碼、GPU時序、長時間負載與使用者黑閃是否解除，仍需安裝新版確認。

測試包：測試15·箭影，com.archery.delaycam.fastresume，versionCode15，0.2.11-fast-return-test，沿用固定簽章，可覆蓋測試14，PR保持draft。
