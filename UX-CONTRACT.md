# Recording and Playback UX Contract

使用者已同意 PM／UI UX 文件提出的錄影與基本播放調整。視覺規則見 [DESIGN.md](DESIGN.md)，需求依據見 [PM 報告](docs/archery-product-review.md) 與 [UI UX 報告](docs/archery-uiux-review.md)。

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
| --- | --- | --- | --- | --- |
| Toast | DelayCamNavHost / RecordingControls / SaveStatusNotice | 本文件 Feedback | 一般短提示；保存狀態持續顯示 | RecordingPlaybackUiTest |
| CRUD | DelayCamViewModel / GalleryRepository / GalleryScreen / DeleteClipAction | 本文件 Playback、Gallery bulk actions | 單段確認；畫廊多選確認與部分失敗保留 | GalleryMultiSelectTest / ReplayReviewDeleteTest |
| Selection | GallerySelection / GalleryScreen | 本文件 Gallery bulk actions | 長按或選取；全選／取消；URI 識別 | GallerySelectionTest / GalleryMultiSelectTest |
| Viewer connection | ViewerScreen / ViewerShareControls / ViewerShareStatus / DelayCamViewModel | 本文件 Multi-device navigation | 獨立頁與練習浮動狀態，共用同一 session | LanViewerIntegrationTest / ViewerNavigationUiTest |
| Sharing | ClipSharing | 本文件 Gallery bulk actions | 單段／多段共用預檢、ClipData 與暫時讀取授權 | GalleryMultiSelectTest |
| Recording | RecordingScreenLayout / RecordingControls | 本文件 Recording | 一般頂部列；橫向練習浮動 compact 版本，共用狀態與按鈕規則 | RecordingPlaybackUiTest / LivePracticeUiTest |
| Capture format | AppSettings / SettingsRepository / CaptureAspectControl / FrameCanvas / VideoAspectNormalizer | 本文件 Capture format | 4:3／16:9；完整／滿版獨立 | CaptureSizeTest / VideoAspectExportTest / RotationContinuityTest |
| Practice controls | LivePracticeLayout / LiveSaveActions | 本文件 Practice Layout and Warmup | 直向下方；橫向非模態可收起面板 | LivePracticeUiTest |

表格、日期輸入、選單、表單與網頁 scrollbar 不適用於這次原生 Android 變更。

## Recording

IDLE → STARTING → RECORDING → STOPPING → IDLE。開始／停止由同一個入口處理，忙碌時禁用重複切換；手勢一次保持只觸發一次，放下後重新啟用。手勢只在練習和即時回放頁作用。

錄影控制置於導航之外，跨 LIVE、Replay、相簿、播放器與設定共用狀態。0.2.14 起只有 LIVE 提供開始／繼續錄影；非 LIVE 且 IDLE 時，沒有保存狀態就不顯示錄影列，有保存狀態則共用 SaveStatusNotice 提供進度／查看／重試。STARTING／RECORDING／STOPPING 保留處理狀態或停止操作，不因瀏覽畫廊而停止錄影。橫向 LIVE 使用 compact 浮動控制，不占導航高度；其他頁與直向保持頂部列。RecordingScreenLayout 讓導航內容保持單一 composition 位置，切換控制樣式不建立第二個導航或播放器。計時使用單調時鐘，請求停止時凍結，不把桌面時間計入。

停止先等待含尾段的片段或編碼器完成事件，再保存。若 5 秒內沒有完成事件，保存已取得的內容並顯示末段未確認，不宣稱完整。保存要求在 Activity 結束時仍盡力完成；程序被系統終止後的復原屬後續範圍。

短期採前景錄影：切背景停止並保存；返回呈現真實保存狀態，由使用者點繼續錄影建立新段，延遲畫面重新準備。螢幕旋轉由同一 Activity 處理組態變更，不停止錄影、不清空延遲緩衝；CameraX 更新目標方向但不重新綁定。編碼器沿用本次拍攝的固定尺寸，完整縮放並清除黑邊區域，避免裁切或殘影。系統程序遭終止等非旋轉重建不保證續錄。

## Practice Layout and Warmup

0.2.6：即時小窗位置由 AppSettings.previewCorner / SettingsRepository 擁有，設定頁顯示左下與右下，變更立即生效且保存。大小／比例由 LiveScreen 擁有，PracticeCameraLayout 按其回報的完整尺寸配置唯一 PreviewView，避免套第二次固定 3:4 黑邊。保存回饋置於小窗相反側，預留設定面板的空間。

延遲主畫面沿用 VideoPlayer，透過 fitActivePicture 明確變體只移除本編碼器已知的內部黑邊。Surface 提交時間與编码 PTS 對應 FrameShapeTimeline；SegmentInfo 只對整段比例一致的片段記錄 contentAspectRatio，播放 MediaItem 攜帶比例。UI 跟隨正在播放的片段，與當下手機方向解耦；混合／未知片段回完整 FIT。縮放只移除已知黑邊，不拉伸、不裁身體、不改 MediaCodec，不清除緩衝。既有保存檔與相簿仍顯示完整編碼畫布；此輪不重編舊影片。

依使用者對修正版 3 的回饋，暖機保留轉圈圈和明確剩餘秒數，說明正在建立延遲畫面；倒數到 0 仍等待第一個延遲畫格，不能僅因計時結束宣告已就緒。狀態仍由 ViewModel 的 warmupRemainingSec／onRenderedFirstFrame 擁有。

LivePracticeLayout 在寬大於高的可用區域讓影片占滿安全區內全部寬、高，取消上一版固定側欄。操作面板預設收起，由「操作」展開；面板覆蓋影片邊緣而不重新量測播放器尺寸，按「收起」或系統返回關閉。它是非模態面板，不鎖定焦點、不阻斷其他操作；展開／收起本身不保存、不改變錄影、不導頁。窄橫向亦適用，面板可捲動。直向保持既有上下配置。使用 FIT，不裁掉身體；來源比例不符螢幕時仍保留黑邊。

左上錄影時間、停止／開始和整段保存結果不隨面板收起；單箭保存結果在小窗相反側共用 SaveStatusNotice 顯示，成功查看與失敗重試皆保留。LiveSaveActions 使用同一保存動作與禁用規則。橫直向即時小窗皆預設顯示，共用一次手動顯示選擇；旋轉不改變開關。暖機仍由同一 DelayWarmupIndicator 顯示，不省略秒數。LivePracticeUiTest 驗證完整寬高、面板開關及返回、短橫向捲動、1.5 倍字體、錄影停止與保存回饋、直向和暖機狀態。RotationContinuityTest 使用真正 MainActivity、CameraX 與編碼器，驗證橫向開啟、旋轉保留緩衝／錄影、小窗開關延續、錄影可解碼及真正切背景的重置。FrameCanvasTest 驗證尺寸改變仍完整呈現並清除殘影。

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

## Replay review and shot deletion (0.2.7)

MediaReviewLayout 擁有回放／已保存影片的橫向滿版、可收起浮動操作與始終可達的返回；控制展開取消回放自動返回，不變更播放器尺寸。RecordingScreenLayout 的 compact 政策同樣用於橫向回放與已保存播放器。SaveStatusNotice 的 trailingAction 變體提供回放「刪除單箭」。DeleteClipAction 共用確認／保留、忙碌禁用、持續錯誤及重試；成功核對 MediaStore 後清除對應保存結果、更新相簿並返回。回放刪除取消自動返回倒數；保留不刪、不跳頁。Android 需要額外刪除授權時由系统 IntentSender 流程處理，取消保留影片。

SegmentInfo.playbackItem 把有效影像比例傳到私有回放；匯出由 ClipPresentationStore 保存精確時間範圍與比例，已存影片的 PlayerScreen 跟隨位置放大。混合／未知片段不裁畫布，舊影片沒有比例資料時仍完整 FIT。刪除影片一併清理其展示 metadata。ReplayReviewDeleteTest 驗證真的存檔、回放元資料、滿版／面板不改尺寸、確認保留／刪除，以及 MediaStore 和相簿結果；ClipPresentationTest 驗證匯出時間／混合比例與刪除清理。

## Picture mode (0.2.8)

使用者要求保留原比例並可自行選擇滿版。AppSettings.pictureMode / SettingsRepository 是持久化來源；PictureModeControl 使用可選語意按鈕；VideoPlayer / activePictureScale 是延遲、單箭回放與已保存播放器的共用縮放擁有者。FIT 預設，FILL 等比填滿並裁邊，設定說明只有觀看裁切、錄影仍完整。舊設定與未知值回 FIT；即時小窗保持完整構圖。變更不清空緩衝、不重建播放器／相機、不改 MediaStore 原片。ActivePictureFitTest 核对完整／滿版涵蓋範圍與比例；RotationContinuityTest 驗證設定持久化、實際 PlayerView 模式及相機／緩衝不中斷；ReplayReviewDeleteTest 保存完整比例，切換兩種模式但保留 MediaItem 與緩衝世代，並產出對照截圖。

## Capture format (0.2.9)

使用者確認將取景比例（4:3／16:9，直拿3:4／9:16）與觀看方式分開。AppSettings.captureAspect / SettingsRepository 擁有持久選擇，缺少／未知值為4:3；既有 pictureMode 保留。CaptureAspectControl 共用 Material 可選按鈕，錄影 STARTING／RECORDING／STOPPING 期間停用並說明；ViewModel 亦阻擋同一變更。比率變更不重建相機／編碼器，不清空緩衝；MainActivity 仍用4:3相機來源，FrameCanvas 中心裁切新影格到所選比例。即時 PiP 同一中心裁切，延遲跟隨正在播放的片段，不能因選了新比例回溯改舊影格。

ClipExporter 與完整錄影共用 VideoAspectNormalizer：保留固定編碼器畫布，用各來源範圍的比例，必要時於保存轉成固定開始比例／方向的標準MP4。已知內部黑邊去除；不同方向等比放入同一輸出畫布，混合未知來源保留完整畫布；不以當下螢幕方向裁掉舊片段。整段錄影以開始時取景比例／方向固定，保存期間顯示真實 SAVING，完成 MediaStore 才成功。轉換／存檔失敗保留可重試來源，不宣稱已保存。觀看 FILL 只再裁切顯示，不改保存格式。舊影片不重編。

ViewModel settings 收集忽略尚未保存的舊值，避免快速切換時先前 DataStore 回覆覆蓋最新選擇；相同值不再寫入。設定頁介面語言仍為繁中，不新增權限。

## Capture quality (0.2.10)

延遲影像以1280×960的4:3來源為目標（16:9中心裁切1280×720，直向720×1280），CameraX仍會與Preview協商，裝置尺寸不足時向下選擇。ResolutionFilter限制來源長邊1280、短邊960；無任何預算內尺寸才用裝置最小尺寸。CameraX原生RGBA轉換取代JPEG；ImageProxyConversion處理rowStride、buffer offset及缺少尾端padding，旋轉後釋放暫存。手勢辨識使用長邊最多640的獨立影像，手勢不持有原始高解析Bitmap；測試15撤回提交後立即recycle，讓其隨GPU／原生引用正常回收，避免非同步繪製讀取已釋放像素。

連續編碼器的畫布改為來源長邊的正方形，上限1280，初始化先查詢AVC支援；不支援時向下選擇並記錄fallback。橫直切換及取景比例變更都保留同一MediaCodec與緩衝，不重新暖機。已知片段比例沿用共用播放與匯出政策去除內部黑邊；存入相簿的標準MP4仍使用所選4:3／16:9，不對外承諾正方形影片。混合／未知比例仍完整顯示畫布。舊影片不重編、不提高細節。30fps僅編碼設定，實際交付幀率、溫度、功耗及1080p仍需實機驗收。


## Continuous delay playback (0.2.11)

DelayedPlaybackController只對首次開始播放計時（首段endedAt＋delay，維持原有初次延遲），完成的片段立即加入同一播放清單並持有lease；計時前維持IDLE、不prepare，不提早顯示畫格。開始後由連續媒體時間軸播放已預載的下一段，取代每段分別延後加入導致佇列耗盡／重新緩衝。沒有未完成檔案進入清單，不新增相機／編碼器／播放器。重置取消首次timer、清空佇列與釋放lease；快速返回延續原有目標來源時間；真正捕捉缺段仍可能等待，下一段到達時由ENDED接回。MainActivity不再於硬體Canvas提交後立即recycle高解析Bitmap；GPU尚未完成讀取時由正常引用生命週期保護像素。實際手機黑閃的因果仍需新版實測，不能以自動測試宣稱已確認所有原因。


## Requested camera source (0.2.12)

使用者明確要求依設定向手機要求16:9／4:3來源，16:9無法使用時回到4:3。CaptureQuality / CameraSourcePolicy是來源尺寸／退回順序的擁有者；MainActivity只在初次進入或取景比例變更重綁Preview＋ImageAnalysis。兩者要求相同比例，16:9以1280×720、4:3以1280×960為目標，KEEP_ONLY_LATEST／RGBA及有上限的解析度選擇維持。16:9只接受該比例來源，失敗清理候選後僅試一次4:3，權限與取消不重試；兩者失敗由來源狀態顯示重開方向，下一次前景可重試，不自動改寫使用者的captureAspect設定。退回4:3來源時，若仍選16:9輸出就依原取景裁切；無法保證感光元件視角或無裁切。

ViewModel的previewSource及cameraSourceStatus是預覽use case與實際來源狀態的共享來源；CaptureAspectControl顯示目前協商比例、切換與失敗。首次相機綁定等待DataStore設定就緒，不先用預設值綁一次。設定改變可能有短暫相機空窗，主編碼器、前景session、已捕捉緩衝與播放器不主動重置；錄影中依既有規則禁止切換。LiveCameraPreview在同一PreviewView中以DisposableEffect接／解不同Preview的SurfaceProvider，不因來源切換重建小窗。旋轉只改targetRotation；其他設定不重綁。舊analyzer以identity拒絕已脫離來源的影格。

驗證由CameraSourcePolicyTest、CameraSourceRequestTest與RotationContinuityTest負責，含真實CameraX協商與輸入比例、受控模擬的16:9不支援分支、同PreviewView持續影像、來源切換保留編碼器／緩衝、錄影中禁用與旋轉不中斷。模擬器上的受控失敗不宣稱等同所有手機的不支援情況。


設定持久化（0.2.12）：ViewModel仍立即更新畫面與pendingSettings，寫入依Main呼叫順序取得Mutex，於IO執行DataStore transaction，避免較舊的快速切換請求落在最後。來源切換仍跟隨即時設定，DataStore舊回覆不蓋過尚未確認的最新值。SettingsPersistenceTest以唯一的最後偏好驗證完成寫入及新ViewModel還原，回放FIT／FILL像素與持久化原有斷言維持。

## Gallery bulk actions (0.2.13)

Canonical owners: GalleryScreen owns presentation and confirmation; GallerySelection owns URI selection transitions; DelayCamViewModel owns gallery refresh, progress, batch deletion and authorization; GalleryRepository owns Movies/Archery/ media access; ClipSharing owns both gallery and saved-player sharing intents. No alternate batch deletion path is introduced in PlayerScreen.

長按選取第一段，之後點擊切換；全選僅選目前成功載入的畫廊影片，不會自動選入新保存影片。零選取時停留選取模式但禁用分享／刪除。返回或取消退出，忙碌時返回離頁但任務繼續。刪除前確認精確數量；成功清除保存結果引用及畫面比例 metadata，失敗保留選取。Android 11+ 將需要權限的 URI 分組交給系統刪除確認並逐一驗證結果；Android 10 授權後重試該項。取消系統確認不回復已完成刪除，也不繼續彈出後續授權。

分享使用 ACTION_SEND 或 ACTION_SEND_MULTIPLE、video/mp4、每個 URI 的 ClipData 和暫時 READ 授權。預檢失敗保留選取且顯示錯誤；不自動傳送或指定收件人。重新整理失敗不清空畫廊；成功更新只移除已不存在的選取。驗證：GallerySelectionTest、GalleryMultiSelectTest，以及既有單箭刪除、播放與旋轉測試；其他 App 所有權與 Android 10 系統授權需實機驗收。


## Recording entry scope (0.2.14)

DelayCamNavHost 依目前路由傳遞 RecordingControls.allowStart，僅 Routes.LIVE 為 true。RecordingControls 擁有同一錄影狀態與非練習頁變體；不在 GalleryScreen 額外隱藏或重做錄影列。沒有錄影／保存內容時不佔頂部高度，RecordingScreenLayout 的唯一導航 composition 位置不變。GalleryRecordingEntryTest 驗證真實導航練習→畫廊→返回→設定，並以共用元件驗證錄影停止、忙碌禁用與保存進度／成功／重試，確保沒有開始／繼續入口。


## LAN viewer trial (0.3.0)

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
| --- | --- | --- | --- | --- |
| Viewer session | DelayCamViewModel / LanViewerServer | 本節 | 手動開啟／關閉；背景撤銷；192-bit 本次觀看連結 | LanViewerServerTest / LanViewerIntegrationTest |
| Viewer media | ViewerSegmentSource / ImmutableSegmentStore | 本節 | 已完成片段、獨立讀取 lease、GET/HEAD/Range | LanViewerServerTest / 實際相機 HTTP 與 Chromium 解碼 |
| Browser playback | viewer.js | 本節與 DESIGN.md | 開始、FIT/FILL、接回目前延遲；只讀 | scripts/test-lan-viewer.py |

只有 LAN_VIEWER_TRIAL 版本提供手動觀看連線；預設不開 server，拍攝與錄影仍由原本單一相機／編碼器擁有。開始前說明僅自己的 Wi-Fi／熱點、只讀觀看、影片保存在拍攝手機。連線網址含本次隨機 token；本輪不記住永久裝置，不自動重放控制指令，沒有遠端保存／錄影或檔案庫讀取路由。HTTP 本機明文，不用公開或不可信網路，不開外網服務。以明確 App 開啟／關閉入口控制觀看權限。

背景、停止分享及 ViewModel 結束關閉 sockets 與撤銷 token；旋轉沿同一 server／相機工作階段。需要重新開啟時產生新連結；舊連結不能讀取新工作階段。metadata 不包含檔案路徑，影片讀取先取得不可覆寫 lease，過期片段為410。提供單一 Range／HEAD，拒絕任意路徑、寫入及跨來源請求。

瀏覽器選同一來源目標時間（拍攝時間減 delaySec），不再加第二次延遲。網路與片段交接可能增加等待；累積落後過多時接回目前目標，不播放全部積壓歷史。載入後確實解碼首畫格才換掉保留畫面；中斷會標示最後畫面。輪詢在分頁隱藏停止、回前景恢復，epoch／delay 改變重新建立觀看。操作面板 Escape 還原按鈕焦點；不干擾拍攝端手勢與本機錄影。

本輪驗收以電腦 Chrome／Edge 為目標，Chromium 自動測試；真實 Pixel→電腦 Wi-Fi／熱點由使用者試裝驗收。不能以 localhost 或模擬器測試宣稱已完成實際無線連線、Safari 相容性、永久配對或遠端控制。

電腦首次授權由 LanViewerServer 的 JoinRequest 與 ViewerShareControls 擁有：電腦輸入短位址，POST 隨機192-bit要求ID、顯示六位核對代碼；手機只在本機允許／拒絕。未批准前輪詢不回傳觀看 token，允許後才導向本次觀看頁。要求重送按同ID冪等，最多四個待確認／24個短期項目，新要求清除三分鐘前項目；停止連線使全部失效。不存在遠端授權／相機控制路由。LanViewerServerTest、LanViewerIntegrationTest 與瀏覽器測試涵蓋拒絕、重試、核對、允許前無媒體與允許後觀看。


## Multi-device navigation (0.3.1)

依使用者確認的本次 IA review：Routes.VIEWER 僅出現在 LAN_VIEWER_TRIAL；依0.3.2使用者決定，僅練習入口導航到 ViewerScreen，設定不再提供捷徑。一般返回回到來源頁，「返回練習」清除練習上方路由。切入此頁暫停練習手勢，但不停止相機、重建編碼器、重暖機或重啟 LAN server；離頁不撤銷連結。明確關閉或 App 背景仍依既有政策撤銷。

連線開啟與待確認由同一 viewerShare StateFlow 擁有。練習頁以 ViewerShareStatus 浮動顯示可點選狀態；待確認顯示數量，點入同一頁核對，允許／拒絕後數量同步。操作面板展開時由入口提供相同數量；不自動跳走、彈出模態或播放提示音。狀態只代表 server 可供觀看，不等同有裝置在線；不顯示無法量測的已連線台數。

待確認先於網址與次要內容；短位址是主要連線方式，QR／完整連結／其他網路位址預設收合，沒有位址時仍提供重新取得連結。權限模型不變，QR 與完整連結包含本次觀看權限，必須明示；沒有永久記住裝置。啟動中禁用重送，錯誤持續顯示可重試。此頁不提供開始／繼續錄影；既有全域停止與保存回復仍可使用。


## Hotspot addresses and expired links (0.3.2)

使用者確認分享網址時曾切到通訊App，兩張截圖的port亦不同；這能確認曾重新開啟，不能單憑10.144位址斷言一定選到行動網路。每次重新開啟仍使用新ServerSocket與隨機token；舊網址失效，沒有改為背景分享或永久連結。重新取得連結只重新列出目前位址，不重建server或token。

LanViewerNetwork 讀取 ConnectivityManager transport/interface 與 NetworkInterface，LanAddressPolicy 排除 cellular／VPN 已知介面與位址、rmnet及v4-rmnet等別名，優先熱點與已知LAN介面；保留可用的RFC1918 IPv4，包含合法的10開頭Wi-Fi，沒有候選就顯示無位址。ACCESS_NETWORK_STATE是普通權限，只供分類網路，不新增定位／附近裝置或熱點控制權限。未知OEM介面與真實熱點仍需實測。

onAppBackgrounded關閉實際觀看連線後保留失效notice；再次開啟清除notice，busy／error及本機批准流程不變。設定的多裝置捷徑與對應導航callback刪除；練習入口仍可達。LanAddressPolicyTest驗證CLAT、vendor傳輸分類、有效10/8、優先順序及無LAN；原生導航／實際server測試驗證無設定入口、背景失效notice、refresh保持token與旋轉不中斷。


## Background sharing (0.4.0, supersedes 0.3.x)

來源：使用者2026-10-19同意「開啟分享後可切換App、連結有效、返回不重新暖機、常駐通知有停止入口」。保留既有只讀批准與本次token模型，不開放遠端控制；背景持續拍攝僅由明確開始多裝置分享啟用，不推廣到一般單機練習。

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
| --- | --- | --- | --- | --- |
| Background camera | DelayCamApp / CameraCaptureController / ViewerForegroundService | 本節 | 可見Activity或已提升camera FGS，非分享維持離開停止 | ViewerBackgroundSharingTest / RotationContinuityTest |
| Preview surface | PreviewSurfaceRouter / LiveCameraPreview | 本節 | 前景PreviewView，背景排空ImageReader | 實際背景MP4持續與Activity重建 |
| Background notice | ViewerForegroundService / ViewerShareControls | 本節 | 相機通知、待確認、停止、通知封鎖後App入口 | ViewerBackgroundSharingTest |

啟動順序：前景使用者操作 → busy與generation → startForegroundService → camera型別startForeground成功 → 開啟HTTP並發布網址。未成功提升服務時不承諾背景；快速離開、超時、權限／啟動失敗或晚到的舊generation回呼不得留下可用舊網址或孤兒服務。通知不含觀看token，服務不export，不由開機或背景自動建立拍攝模型，START_NOT_STICKY不重啟。

切背景時保留同一captureSession／MediaCodec／server／token，持續發布新MP4與錄影，停用手勢和回放自動返回；隱藏的本機延遲播放器釋放片段讀取lease，不累積不可修剪的佇列。回到實際練習路由才從保留緩衝接回目前延遲；通知進連線頁不因沒有影片Surface而誤判播放器停滯並清空相機緩衝。真正缺段或解碼失敗仍沿用安全退回準備。

相機生命週期與Activity分離；切換App、一般旋轉、Activity重建不得重綁相機或清掉拍攝／分享session。返回通知走同一ViewerScreen，不建立另一套批准介面。待確認在通知只顯示數量，核對代碼與允許／拒絕仍在App完成。

停止分享：立即撤銷server/token與停止前景通知；Activity可見時維持普通練習，相機在背景時結束本次拍攝並保存整段錄影。滑掉App或服務被系統終止時也撤銷，不自動恢復分享。程序遭強制終止不承諾連結／錄影可復原。最後Activity不存在且無服務時，等待進行中的保存完成再清理ViewModelStore、CameraX、編碼器、播放器與wake lock。

Android 13+通知權限可在首次開始時請求；拒絕不作為FGS啟動的必要條件，App提供持續說明及停止入口。前景服務與相機類型、wake lock是普通manifest權限；既有相機權限仍必要，不新增麥克風、位置、雲端傳輸或永久配對。


背景返回的片段驗證（0.4.0）：系統忙碌或Preview Surface交接可能產生超過5秒但時間與媒體長度一致的完整GOP。僅背景返回使用上限15秒的明確變體，並將上限傳給後續播放佇列；一般回放仍維持5秒上限。重複檔名、重疊、超過150ms的間隙、媒體與來源時鐘差超過500ms、來源過舊或目標尚未完成仍不接受。背景目標尚未可用時先保留來源緩衝、顯示等待首畫格並於新片段到達重試；可見練習頁等待15秒仍無可用資料才安全退回準備，不因剛超過5秒就清掉相機歷史。通知可見性同時檢查App通知與分享通知頻道；測試用確實生效的頻道封鎖，不用無效的AppOps假定通知已禁用。
