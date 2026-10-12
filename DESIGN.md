---
version: alpha
name: "Archery Delay Camera"
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

定位是手機架上的攝影工具：使用者放下弓後，看剛才的一箭，也保存影片供事後研究。沿用原生 Android、黑底和橙色，不重新塑造品牌。具體用途由使用者確認，觀看距離與戶外效果仍待實測。

介面使用繁體中文；不推定日本市場需求。特色是大幅完整動作畫面與清楚的時間、錄影狀態。動態效果僅用於必要的準備與保存提示。

Runtime tokens 是唯一色彩來源：`ui/theme/Color.kt` → `ui/theme/Theme.kt` → `MaterialTheme.colorScheme` → 共用元件。本文件記錄現有色票，不生成另一套 CSS。前述 component px 僅供文件格式表達；Android 實作對應 dp，不是實體像素。

Theme.kt 同時設定全域 LocalContentColor 為 onBackground，讓直接位於深色背景的 Text 保持可讀；半透明暖機 Surface 明確使用 onSurface，避免透明色無法對應色票而退回黑色文字。

## Colors

橙色代表主要操作，黑底讓影片優先；面板採現有深灰。錄影和錯誤都搭配文字，不只靠紅色傳達。停止錄影採次要按鈕；刪除則在確認對話框清楚標示。

## Typography

沿用 Material 3 Typography 與 Android 系統字型回退。標題和錄影時間採 titleSmall；操作採 Material 按鈕文字；說明採 bodySmall。字體放大時可換行，不裁掉關鍵狀態。

## Layout

錄影控制跨頁共用，一般頂部列最小 64 dp；橫向練習改為左上角浮動緊湊版本，不預留頂部高度。錄影按鈕至少 48 dp，練習主要保存按鈕至少 56 dp。全域遵守 safeDrawingPadding。影片使用 FIT，不以填滿裁掉身體。

練習 delay 頁在可用寬度大於高度時，影片使用完整可用寬、高。使用者回饋上一版仍太小，因此取消固定側欄與頂部列的空間保留；控制以右上角「操作」展開，220–300 dp 的非模態浮動面板可捲動。展開／收起不改變影片尺寸，不重新建立播放器；返回鍵優先收起面板。面板不鎖焦點，收起按鈕保持同一位置的語意節點。一般狀態與設定收進面板；左上角錄影時間和開始／停止始終可操作，單箭保存結果留在左下角，失敗不因收起消失。包括小於 600 dp 的窄橫向視窗。直向維持上下配置。

即時小窗在橫向預設收起，操作面板可再開啟；顯示時位於右下角，隨影片高度縮至 64–96 dp 寬。直向預設顯示並沿用慣用手位置。兩種方向分別記住開關，減少對延遲影片的遮擋。

暖機使用轉圈圈與中央大字剩餘秒數，並說明正在建立設定的延遲；到 0 秒仍顯示等待首畫格，實際呈現首畫格才移除。

回看在寬度至少 600 dp 且寬大於可用高度時，採影片與可捲動控制欄並排；其他尺寸採上下排列，控制區可捲動。時間軸另有前後 5 秒按鈕，提供非拖曳操作。

## Elevation & Depth

使用既有 Surface 與 Material 對話框。橫向浮動操作與錄影控制使用現有 surface 色的 94% 不透明度並明定 onSurface 字色；不新增品牌色與裝飾陰影。常駐手勢教學移至操作說明。

## Shapes

採 Material 3 Shapes；沒有為單一頁面建立新的裝飾卡片或陰影。

## Components

`RecordingScreenLayout` 擁有頂部列／浮動錄影的空間配置，導航內容只有一個 composition 位置。`LivePracticeLayout`、`LiveSaveActions` 與 `DelayWarmupIndicator` 擁有練習頁的橫直配置、非模態操作面板、保存按鈕和暖機倒數。`LiveCameraPreview` 保持同一 PreviewView 與相機 Surface 連接，收起僅隐藏顯示並移除輔助功能語意，不中斷相機。`RecordingControls` 是錄影操作與結果的共用元件，其 compact 版本共用狀態與切換規則；`SaveStatusNotice` 是單箭保存結果；`VideoPlayer` 與 `PlaybackControls` 是兩種回看共用的影片與控制層。狀態行為見 [UX-CONTRACT.md](UX-CONTRACT.md)。

所有主要操作有文字。忙碌時禁用重複操作；成功由使用者點查看，不自動帶離練習；失敗持續顯示，能重試時才提供重試。鏡像只作用於影片層。

## Do's and Don'ts

- 優先完整動作、錄影狀態和保存結果。
- 保留手動操作；只保存與存檔並回看名稱明確。
- 不把可回放等同已保存，不把慢速播放等同高 fps 拍攝。
- 不增加常駐分析面板、裝飾動畫或工程術語。
