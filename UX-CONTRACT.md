# Recording and Playback UX Contract

使用者已同意 PM／UI UX 文件提出的錄影與基本播放調整。視覺規則見 [DESIGN.md](DESIGN.md)，需求依據見 [PM 報告](docs/archery-product-review.md) 與 [UI UX 報告](docs/archery-uiux-review.md)。

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
| --- | --- | --- | --- | --- |
| Toast | DelayCamNavHost / RecordingControls / SaveStatusNotice | 本文件 Feedback | 一般短提示；保存狀態持續顯示 | RecordingPlaybackUiTest |
| CRUD | DelayCamViewModel / PlayerScreen | 本文件 Playback | 手動開啟保存結果；刪除確認 | RecordingPlaybackUiTest / 裝置驗收 |
| Recording | RecordingScreenLayout / RecordingControls | 本文件 Recording | 一般頂部列；橫向練習浮動 compact 版本，共用狀態與按鈕規則 | RecordingPlaybackUiTest / LivePracticeUiTest |
| Practice controls | LivePracticeLayout / LiveSaveActions | 本文件 Practice Layout and Warmup | 直向下方；橫向非模態可收起面板 | LivePracticeUiTest |

表格、日期輸入、選單、表單與網頁 scrollbar 不適用於這次原生 Android 變更。

## Recording

IDLE → STARTING → RECORDING → STOPPING → IDLE。開始／停止由同一個入口處理，忙碌時禁用重複切換；手勢一次保持只觸發一次，放下後重新啟用。手勢只在練習和即時回放頁作用。

錄影控制置於導航之外，因此跨 LIVE、Replay、相簿、播放器與設定維持一致。橫向 LIVE 使用 compact 浮動控制，不占導航高度；其他頁與直向保持頂部列。RecordingScreenLayout 讓導航內容保持單一 composition 位置，切換控制樣式不建立第二個導航或播放器。計時使用單調時鐘，請求停止時凍結，不把桌面時間計入。

停止先等待含尾段的片段或編碼器完成事件，再保存。若 5 秒內沒有完成事件，保存已取得的內容並顯示末段未確認，不宣稱完整。保存要求在 Activity 結束時仍盡力完成；程序被系統終止後的復原屬後續範圍。

短期採前景錄影：切背景停止並保存；返回呈現真實保存狀態，由使用者點繼續錄影建立新段，延遲畫面重新準備。螢幕旋轉由同一 Activity 處理組態變更，不停止錄影、不清空延遲緩衝；CameraX 更新目標方向但不重新綁定。編碼器沿用本次拍攝的固定尺寸，完整縮放並清除黑邊區域，避免裁切或殘影。系統程序遭終止等非旋轉重建不保證續錄。

## Practice Layout and Warmup

依使用者對修正版 3 的回饋，暖機保留轉圈圈和明確剩餘秒數，說明正在建立延遲畫面；倒數到 0 仍等待第一個延遲畫格，不能僅因計時結束宣告已就緒。狀態仍由 ViewModel 的 warmupRemainingSec／onRenderedFirstFrame 擁有。

LivePracticeLayout 在寬大於高的可用區域讓影片占滿安全區內全部寬、高，取消上一版固定側欄。操作面板預設收起，由「操作」展開；面板覆蓋影片邊緣而不重新量測播放器尺寸，按「收起」或系統返回關閉。它是非模態面板，不鎖定焦點、不阻斷其他操作；展開／收起本身不保存、不改變錄影、不導頁。窄橫向亦適用，面板可捲動。直向保持既有上下配置。使用 FIT，不裁掉身體；來源比例不符螢幕時仍保留黑邊。

左上錄影時間、停止／開始和整段保存結果不隨面板收起；單箭保存結果在左下共用 SaveStatusNotice 顯示，成功查看與失敗重試皆保留。LiveSaveActions 使用同一保存動作與禁用規則。橫直向即時小窗皆預設顯示，共用一次手動顯示選擇；旋轉不改變開關。暖機仍由同一 DelayWarmupIndicator 顯示，不省略秒數。LivePracticeUiTest 驗證完整寬高、面板開關及返回、短橫向捲動、1.5 倍字體、錄影停止與保存回饋、直向和暖機狀態。RotationContinuityTest 使用真正 MainActivity、CameraX 與編碼器，驗證橫向開啟、旋轉保留緩衝／錄影、小窗開關延續、錄影可解碼及真正切背景的重置。FrameCanvasTest 驗證尺寸改變仍完整呈現並清除殘影。

## Playback

0.2.3 的回放手勢與觸控共用 PlaybackSessionController：拇指朝下返回練習，食指朝上／V 手勢加快／放慢一檔（0.25×、0.5×、1×），五指重播同一影片第一段，保留速度與鏡像，不再次保存。LIVE 五指仍保存並回看，比讚仍開始／停止整段錄影；播放手勢不在相簿、設定或背景執行。

GestureCommandGate 依場景與版本拒絕舊辨識結果。切頁與每次觸發後，需要中立姿勢／手離開穩定 300 ms 才重新啟用；單幀消失、低信心的同一控制姿勢不當成放手。保存／比讚沿用 350 ms 與 3 秒冷卻，回放動作使用 600 ms、800 ms 冷卻。張掌保留既有 0.55 門檻，其他控制初始 0.70，射箭站位可靠性待實測。

第一次未操作的回放播完留最後畫格兩秒，明確顯示返回剩餘秒數；變速、重播、暫停、拖曳、鏡像或開啟說明會取消自動返回，直到明確返回。切背景取消倒數，回前景的未操作結束影片給新的兩秒，不執行背景累積的返回。原本保存／全段錄影仍獨立，返回不取消匯出。0.2.3／未啟用試驗開關的建置仍安全 reset 重新暖機。獨立 0.2.4 快速返回測試版，正常同一前景與延遲設定下優先使用有效緩衝接回現在減延遲的位置；缺段、世代變更、錯誤、首畫格逾時或時間漂移退回暖機。提示仍由原有 warmupRemainingSec／onRenderedFirstFrame 擁有，不承諾零等待。詳見 docs/fast-return-comparison.md。

PracticeCameraLayout 擁有跨頁唯一相機 Surface，確保回放手勢看現場相機，而非回放影片。新增原生測試核對切去回放與返回仍有即時畫格、同一 PreviewView，並以模擬手勢事件和實際 Media3 播放器驗證控制；不把事件測試宣稱成真人手勢可靠性驗收。

存檔並回看：保護片段副本 → 即時慢動作 → 保存獨立進行。只保存：留在延遲畫面。正常單次回放結束可返回；使用者暫停、拖曳、跳轉、切速度或鏡像後，停留回放直到明確返回。

即時與相簿使用相同暫停、時間軸、前後 5 秒、0.25×／0.5×／1×與鏡像控制。鏡像選擇由同一 ViewModel 擁有，僅翻轉影片；速度沿用既有設定來源。完整 FIT 取景是本次預設。

查看即時回放的保存結果會離開並清除該回放路由，返回不留下空的回放頁。刪除前必須確認，刪除成功才離開播放器。

## Feedback

SAVING／SAVED／FAILED 分別呈現；MediaStore 完成後才能顯示已保存。單箭與整段結果獨立，存單箭不停止整段錄影。保存失敗保留可重試的副本，不僅顯示短 toast；成功透過查看按鈕由使用者開啟。

一般提示沿用導航的短提示；保存結果使用共用持續狀態，兩者為明確的用途差異。刪除與分享沿用本機影片規則，不增加帳號、雲端或儲存權限。

## Accessibility and Verification

Material 語意按鈕、繁中標籤、48 dp 操作下限、系統安全區、可捲動控制區和非拖曳時間軸替代操作。錄影時間不以 live-region 每秒播報，保存狀態使用 polite live-region。

JVM 測試驗證生命週期、片段時間、跨片段定位和手勢；模擬器測試驗證按鈕、忙碌、保存成功／失敗與直接播放控制。LiveCameraPreviewTest 另以模擬器前鏡頭確認預設隱藏小窗仍有 ImageAnalysis 畫格，開關小窗保持同一 PreviewView 並持續收到畫格。這項測試不等同延遲編碼與完整實機驗收。實際 Pixel 長時間錄影、CameraX／編碼器、日照、護具、觀看距離與完整大字／TalkBack 流程仍需裝置驗收。
