---
version: alpha
name: "箭影"
description: "單人射箭練習的延遲攝影與動作研究工具"
colors:
  primary: "#FF7A1A"
  background: "#000000"
  surface: "#14171C"
  text: "#F4F5F7"
  muted: "#9AA1AB"
  danger: "#FF4D4F"
  success: "#35C46B"
typography:
  sans:
    fontFamily: "Roboto, Noto Sans CJK TC, sans-serif"
omitted:
  - section: rounded
    reason: "沿用 Compose Material 3 Shapes，不建立另一套半徑來源"
  - section: spacing
    reason: "原生介面以 dp 與系統安全區定義，具體尺寸記於 Layout"
components:
  recording-controls:
    minHeight: "64px"
  primary-action:
    minHeight: "56px"
---

# Archery Delay Camera Design

## Overview

品牌名稱為「箭影」；測試版啟動器名稱「測試11·箭影」，測試字樣放在前面以免被截斷。箭與回放環構成主圖示，TEST 綠色徽章區分測試包；設定頁顯示完整版本與建置號。圖示使用專用品牌圖稿，不改變原有介面色彩 token。

定位是手機架上的攝影工具：使用者放下弓後，看剛才的一箭，也保存影片供事後研究。沿用原生 Android、黑底和橙色，不重新塑造品牌。具體用途由使用者確認，觀看距離與戶外效果仍待實測。

介面使用繁體中文；不推定日本市場需求。特色是大幅完整動作畫面與清楚的時間、錄影狀態。動態效果僅用於必要的準備與保存提示。

Runtime tokens 是唯一色彩來源：`ui/theme/Color.kt` → `ui/theme/Theme.kt` → `MaterialTheme.colorScheme` → 共用元件。本文件記錄現有色票，不生成另一套 CSS。前述 component px 僅供文件格式表達；Android 實作對應 dp，不是實體像素。

Theme.kt 同時設定全域 LocalContentColor 為 onBackground，讓直接位於深色背景的 Text 保持可讀；半透明暖機 Surface 明確使用 onSurface，避免透明色無法對應色票而退回黑色文字。

## Colors

橙色代表主要操作，黑底讓影片優先；面板採現有深灰。錄影和錯誤都搭配文字，不只靠紅色傳達。停止錄影採次要按鈕；刪除則在確認對話框清楚標示。

## Typography

沿用 Material 3 Typography 與 Android 系統字型回退。標題和錄影時間採 titleSmall；操作採 Material 按鈕文字；說明採 bodySmall。字體放大時可換行，不裁掉關鍵狀態。

## Layout

錄影控制跨頁共用，一般頂部列最小 64 dp；橫向練習改為左上角浮動緊湊版本，不預留頂部高度。錄影按鈕至少 48 dp，練習主要保存按鈕至少 56 dp。全域遵守 safeDrawingPadding。影片預設使用完整畫面 FIT；設定可選滿版 FILL，等比放大並裁切邊緣，原始录影仍完整。

練習 delay 頁在可用寬度大於高度時，影片使用完整可用寬、高。使用者回饋上一版仍太小，因此取消固定側欄與頂部列的空間保留；控制以右上角「操作」展開，220–300 dp 的非模態浮動面板可捲動。展開／收起不改變影片尺寸，不重新建立播放器；返回鍵優先收起面板。面板不鎖焦點，收起按鈕保持同一位置的語意節點。一般狀態與設定收進面板；左上角錄影時間和開始／停止始終可操作，單箭保存結果留在左下角，失敗不因收起消失。包括小於 600 dp 的窄橫向視窗。直向維持上下配置。

即時小窗在橫直向皆預設顯示，設定頁提供獨立「即時視窗位置：左下／右下」，DataStore 記住選擇；不再以慣用手命名。舊資料有明確慣用手時沿用對應左右邊（右手→左側），沒有資料預設右下。左右切換不重建相機。小窗使用實際相機比例；橫向寬度最多 27% / 240 dp，含標題高度最多 42%；直向寬度最多 42% / 180 dp，含標題高度最多 48%。以兩個上限較小者等比放大，取代 64–96 dp 與固定直式小窗；保存通知移到相反側。橫直向共用顯示開關。

0.2.6 延遲主畫面依「正在播放片段」的有效影像比例消除編碼黑邊造成的二次縮小，完整影像 FIT 到可用空間。編碼尺寸與單一編碼器仍固定，不裁切身體。片段若混合方向／比例未知就完整 FIT 編碼畫布，短暫切換後再放大；不能用當下手機方向推定數秒前的影片方向。真正切背景仍停止保存，返回重新暖機。

暖機使用轉圈圈與中央大字剩餘秒數，並說明正在建立設定的延遲；到 0 秒仍顯示等待首畫格，實際呈現首畫格才移除。

0.2.7 回放與已存影片共用 MediaReviewLayout：橫向影片使用完整安全區，控制以右上「操作」展開／收起，展開不縮影片；返回始終可按，系統返回優先收起面板。錄影控制在橫向回放／播放器亦改為浮動 compact，保存提示及「刪除單箭」位於左下覆蓋，不保留頂部高度。直向保持上下排列與可捲動控制。回放沿用有效影像 FIT，存檔記錄各片段有效影像比例與精確匯出時間，播放器隨時間使用對應比例；舊影片／未知或混合比例保留完整畫布。時間軸與前後 5 秒、速度、鏡像及重播共用既有控制。

## Elevation & Depth

使用既有 Surface 與 Material 對話框。橫向浮動操作與錄影控制使用現有 surface 色的 94% 不透明度並明定 onSurface 字色；不新增品牌色與裝飾陰影。常駐手勢教學移至操作說明。

## Shapes

採 Material 3 Shapes；沒有為單一頁面建立新的裝飾卡片或陰影。

## Components

0.2.3 回放新增手勢返回、變速與重播；映射與放手規則見 UX-CONTRACT.md。回放的速度選擇與手動重播共用 PlaybackSessionController；播放器事件立即同步速度選取狀態。回放手勢教學收進「手勢說明」，只在第一次未操作的回放結束顯示兩秒返回文字；不新增常駐教學面板。原有黑底、橙色、FIT 與橫向滿版練習配置維持。

PracticeCameraLayout 在導航之外保留唯一 PreviewView；LiveScreen 回報即時小窗的位置，host 在該位置顯示相機，在回放或收起小窗時保持透明連接，不重新綁定鏡頭。

`RecordingScreenLayout` 擁有頂部列／浮動錄影的空間配置，導航內容只有一個 composition 位置。`LivePracticeLayout`、`LiveSaveActions` 與 `DelayWarmupIndicator` 擁有練習頁的橫直配置、非模態操作面板、保存按鈕和暖機倒數。`LiveCameraPreview` 保持同一 PreviewView 與相機 Surface 連接，收起僅隐藏顯示並移除輔助功能語意，不中斷相機。`RecordingControls` 是錄影操作與結果的共用元件，其 compact 版本共用狀態與切換規則；`SaveStatusNotice` 是單箭保存結果；`VideoPlayer` 與 `PlaybackControls` 是兩種回看共用的影片與控制層。狀態行為見 [UX-CONTRACT.md](UX-CONTRACT.md)。

所有主要操作有文字。忙碌時禁用重複操作；成功由使用者點查看，不自動帶離練習；失敗持續顯示，能重試時才提供重試。鏡像只作用於影片層。

## Do's and Don'ts

- 優先完整動作、錄影狀態和保存結果。
- 保留手動操作；只保存與存檔並回看名稱明確。
- 不把可回放等同已保存，不把慢速播放等同高 fps 拍攝。
- 不增加常駐分析面板、裝飾動畫或工程術語。

## Picture mode (0.2.8)

SettingsRepository 記住 PictureMode，預設 FIT 保留完整動作；使用者可選 FILL 滿版，依有效影像等比填滿視窗，邊緣會裁切、不拉伸。VideoPlayer 是延遲／回放／已存影片共用擁有者；PictureModeControl 在設定頁提供完整畫面／滿版及明確裁切說明。模式只作用於觀看，編碼與匯出不變，切換不重建播放器／相機、不清空緩衝、不重新暖機。即時小窗維持完整構圖。舊影片未知比例以完整編碼畫布作縮放基準，不能承諾移除原檔內黑邊。
