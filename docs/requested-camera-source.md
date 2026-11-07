# 測試16（0.2.12）：依設定向相機要求來源比例

## 行為

選16:9時，Preview與ImageAnalysis都先要求16:9，目標1280×720；選4:3時要求4:3，目標1280×960。來源尺寸受前鏡頭、CameraX同時串流組合與原有像素預算限制，較低解析度可協商，但不能把4:3偽報成16:9。16:9協商失敗先清理候選，再試一次4:3。權限錯誤與取消不觸發另外一次相機嘗試；兩種比例都失敗會顯示失敗與重開方向，沒有自動無限重試。

實際來源顯示在既有取景比例設定下方。使用者的選擇不被退回流程改寫：若16:9不支援，來源改4:3，仍依所選16:9輸出中心取景（仍可能裁切）。如需完整4:3，改選4:3＋完整觀看。相機原生16:9也可能由底層裁感光元件，不能保證更廣視野。

## 生命週期

App等待DataStore初始設定後才綁定，之後由captureAspect＋recordingPhase觀察來源；只改比例且IDLE時重新協商。旋轉與其他設定不重綁，錄影中原有按鈕及ViewModel防護維持。新的Preview以StateFlow傳給唯一PreviewView，DisposableEffect只接換SurfaceProvider。舊ImageAnalysis先清理並以identity拒絕舊影格。單一固定正方形MediaCodec、前景session、緩衝與播放清單不因換來源主動清除；相機重綁仍可能短暫停頓，真正背景仍依既有規則重建緩衝。

切換本身不增加新的相機use case，仍只有Preview＋ImageAnalysis。test15的預載與GPU影像正常回收策略維持；輸出MP4及取景比例政策沿用。720p為目標，裝置支援與長時間負載需實機驗收。若初次來源尺寸較小，固定編碼畫布也較小；後續提高相機來源尺寸不會重啟編碼器放大畫布，需新的前景session才重新協商編碼尺寸。

## 驗證

新增JVM測試優先來源、單次退回、兩次失敗終止、4:3不繞回16:9、權限／取消與來源比例尺寸。原生測試以真實CameraX前鏡頭驗證4:3→16:9→4:3及相同PreviewView繼續接收RGBA影格，另以受控模擬16:9失敗分支接上真正4:3綁定，並驗證退回文字。原有MainActivity旋轉測試加驗實際16:9來源與保留同一編碼器／緩衝，既有黑畫格與缺段恢復測試不移除。

首次CI 38711772357：52 JVM通過；47原生中46通過，新來源協商／退回／來源提示及MainActivity切來源保留緩衝測試均通過。唯一失敗為既有ReplayReviewDeleteTest等待FILL偏好寫入15秒逾時。設定寫入改成Main按請求順序取得Mutex、IO執行DataStore transaction，避免DataStore transform依賴UI執行緒，並序列化快速切換。增加快速多次設定更改、最後選擇落盤與新ViewModel還原的原生測試；原有逾時斷言不放寬，補上最後觀察值以利診斷。此為針對寫入執行緒與順序的修正，不宣稱已從這份紀錄證明唯一因果。

最終[CI 38713429164](https://github.com/patty60723/delayCamForArchery/actions/runs/38713429164)：來源commit `19edeedd4b632c219f63707df37075f1c7f7e073`，tree `60c38724aa6a9d9cb2fe3820ce1e2cea36a3e511`。52 JVM＋48 Android API34原生測試，共100項，0失敗、0錯誤、0跳過；build與ui-tests均success。DESIGN lint、靜態介面strict audit、固定簽章與套件標籤／正式測試並存安裝均通過。

已下載原生XML、logcat與截圖核對。MainActivity實際紀錄4:3→1280×960、16:9→1280×720，RGBA format=1；旋轉0／270／180維持同一1280×720來源。CameraSourceRequestTest真實來源／退回／同小窗12.278秒、提示與錄影鎖1.129秒；原回放FIT／FILL／直接刪除9.071秒、新設定快速切換與還原3.195秒，均通過。橫向錄影截圖已檢視；模擬器合成圖案不代表手機顏色或鏡頭視角。

[APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38713429164/artifacts/11962025279)（解壓後安裝app-debug.apk） · [原生報告與截圖](https://github.com/patty60723/delayCamForArchery/actions/runs/38713429164/artifacts/11961876728)。artifact至2026-10-29有效。

APK SHA-256：`456e6458b973aeccfb8e70f76e953c4a93e67234ab56ed6530f65adf40a5f84e`；簽章憑證SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`。

一般來源選擇限制長邊1280、短邊960；完全沒有預算內尺寸時仍用最小的相同比例尺寸，可能較大。測試16:9不支援分支為受控失敗＋真實4:3，不宣稱已在不支援16:9的實體手機完成驗收。Pixel實際支援、切換停頓、視角及長時間穩定度仍需手機確認。

套件com.archery.delaycam.fastresume、versionCode16、0.2.12-fast-return-test，啟動器測試16·箭影。沿用固定簽章，驗證通過後可覆蓋測試15。
