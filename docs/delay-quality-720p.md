# 延遲畫質修正：測試14（0.2.10）

## 問題與原因

使用者回報延遲畫面比即時小窗模糊。舊版ImageAnalysis只要求4:3，未明確要求解析度；實際尺寸由CameraX協商，不能從比例推定720p。影像先YUV→JPEG品質90→Bitmap→H.264，多一次有損壓縮。編碼畫布固定在第一個影格的方向，轉向後為放進舊畫布而縮小；播放器再移除黑邊放大，也無法恢復細節。滿版FILL另有裁切放大。

## 實作

- CaptureQuality的CameraX設定：目標1280×960、4:3、優先接近且較小尺寸，KEEP_ONLY_LATEST。來源長邊上限1280、短邊960；異常裝置無預算內尺寸時取最小尺寸。Preview及ImageAnalysis仍是原有兩個use case，無額外串流。
- 由CameraX原生輸出RGBA_8888；ImageProxyConversion直接複製RGBA並旋轉，處理rowStride、pixelStride、buffer offset與最後一列缺少padding，保留原buffer游標。移除JPEG編解碼與品質90限制。
- 編碼畫布用來源長邊正方形，上限1280，16像素對齊。1280×960來源的16:9畫面以1280×720放入畫布；直向720×1280亦可原尺寸容納。查詢MediaCodecList能力，若不支援逐級下降，不每次旋轉重啟codec。
- 同一個持續H.264編碼器、8Mbps、30fps設定；片段仍共用SPS/PPS。正方形黑邊由既有有效影像比例移除；ClipExporter與整段錄影共用VideoAspectNormalizer，輸出真實4:3／16:9 MP4。
- 手勢使用長邊最多640的獨立Bitmap，保持原比例，不持有720p捕捉影像；主影像同步繪至編碼surface後即可recycle。辨識模型、手勢映射、門檻、冷卻不變。
- logcat記錄相機實際寬高／RGBA格式／rotation與編碼canvas／codec／fallback，確認目標有沒有實際協商成功。目標720p不等同所有前鏡頭都能達成720p。

## 成本與限制

1280正方形編碼畫布為1,638,400像素，比1280×720多約78%，黑色區域容易壓縮，但GPU／codec負擔仍較高。限制來源尺寸、保留單一編碼器、降低手勢輸入尺寸並釋放高解析暫存以控制成本；不能據此宣稱Pixel長時間不發熱或不掉幀。實際幀率取決於硬體與負載。1080p未開放，不以提高碼率或放大舊影片冒充畫質提升。

最小替代尺寸可能低於720p，會明確記錄。混合方向的短片段因缺少逐影格比例，仍可短暫保留整個畫布；全程方向／格式鎖定與無重新暖機的規則不變。已保存的舊影片不回溯修改。

## 驗證

新增JVM驗證方向共用畫布、上限與降級順序；原生驗證RGBA顏色／padding／旋轉／越界、手勢副本生命週期、1280畫布有效區域。真實MediaCodec測試從直向開始再轉橫向，檢查同一codec、1280正方形來源、720×1280／1280×720標準MP4，並解碼四像素黑白條檢查細節沒有被轉向縮小抹掉。既有CameraX小窗測試改用正式RGBA與解析度設定，確認收起／回放／返回仍收到能轉換的畫格。其餘回放、刪除、錄影與旋轉流程維持完整回歸測試。

CI：[38692371702](https://github.com/patty60723/delayCamForArchery/actions/runs/38692371702)，實際來源commit `dc7bc3fa396b0370038805d04e3c31449d7a3882`，tree `5718a123088234e707b554f491dcfc33ab6899ad`。47項JVM＋43項Android API34原生測試，共90項，0失敗、0錯誤、0跳過。建置與原生測試job均成功；DESIGN lint、固定簽章與正式／測試包並存安裝檢查通過。

原生報告中720p轉向及細線條輸出測試通過（16.65秒）。實際MainActivity的CameraX log顯示1280×960、RGBA format=1，編碼1280×1280、fallback=false；旋轉到0／270／180度仍維持原編碼器與緩衝，真正背景返回才依原規則建立新session。這是模擬器結果，不代表已量到使用者Pixel的實際解析度。原生報告及橫向截圖已下載核對。

[APK ZIP](https://github.com/patty60723/delayCamForArchery/actions/runs/38692371702/artifacts/11952383000)（解壓縮後安裝app-debug.apk）；[原生測試報告與截圖](https://github.com/patty60723/delayCamForArchery/actions/runs/38692371702/artifacts/11953470688)。APK artifact至2026-10-29有效。

APK SHA-256：`ceff50d95c8fed9e5fdc98de9dff211605569bc1e9f5a360cdc6e241ce2d0e81`；簽章憑證SHA-256：`a2e1d47174b2a24a99045fd4795ba37f97f115d3143733067a4859ce8555b042`。

測試包識別：測試14·箭影，com.archery.delaycam.fastresume，版本0.2.10-fast-return-test，versionCode14。沿用固定簽章，通過簽章檢查後才提供下載，可覆蓋測試13並保留原設定及影片；正式舊包可並存。
