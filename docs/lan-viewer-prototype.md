# 熱點與瀏覽器觀看：只讀分段原型（尚未交付）

下一項沿 PM 文件的熱點＋瀏覽器多裝置優先順序，先解除跨裝置媒體播放的不確定性。拍攝端 Android；觀看端候選 Android Chrome、iPhone／iPad Safari 與電腦。此原型的新增程式仍待編譯、HTTP／瀏覽器與三平台真機驗證，不能稱為已完成串流。

## 已開始的具體實作

- ViewerSegmentSource 讀取已完成不可覆寫的 MP4 片段、來源開始／结束時間、實際播放長度、有效比例與來源 epoch。HTTP 每次讀檔先 acquire ImmutableSegmentStore.Lease，完成後釋放；不另開相機或編碼器。
- LanViewerServer 提供本次隨機 192-bit token 連結、只讀 status／segment／viewer assets。支援 GET／HEAD、單一 byte Range 和 suffix，拒絕多 Range、任意路徑及控制寫入。只接受私有位址與 loopback，無 CORS、外部資源或公開服務。
- 設定頁手動開始／停止、區網位址、QR Code、複製觀看連結。預設關閉；token 是本次觀看權限，停止或切背景撤銷，不把 IP 視為永久裝置身分。HTTP 是區網明文，只在自己的 Wi-Fi／熱點使用；TLS 與永久配對另作設計。
- 瀏覽器首次點擊開始、playsinline／muted、依拍攝端時鐘計算目標來源時間，延遲只設定一次。資料不足呈現真實準備，斷線重新查狀態，來源 epoch 改變／背景回來接回目前延遲。不提供未驗證的精確延遲保證。
- 观看全视窗及可收合控制、完整畫面／滿版。此版以獨立 MP4 切換，可能出現片段間停頓，不稱為無縫 HLS／WebRTC。
- 已寫 JVM HTTP／Range／授權路徑／過期片段測試，及相機旋轉保持分享、背景停止的測試變更；尚未執行並通過。

## 下一關卡

1. 修正編譯與測試、沿 Color.kt 建立瀏覽器 token adapter，補 DESIGN／UX-CONTRACT。
2. Chromium 實際連續解碼與中斷復原；Safari 真機驗證首次播放、Range、背景／橫直切換；至少一台實際熱點連線。
3. 量測片段切換與端到端延遲；若停頓不適合射箭觀看，改用重封裝 HLS／fMP4，再決定是否需要 WebRTC。不可為了宣稱串流成功而忽略停頓。
4. 之後加入記住配對、快速再連與單一控制者的 requestId 冪等指令。現在沒有這些功能，不沿用或自動重送錄影指令。

本次先交付 0.2.8 滿版設定。多裝置原型保持獨立，原始影片仍保存在拍攝手機。
