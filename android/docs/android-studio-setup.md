# 用 Android Studio 開發 / 建置 / 跑到實機

最省事的路線——Android Studio 會幫你處理掉 [`vscode-setup.md`](vscode-setup.md)
裡大半要手動做的事(裝 JDK、裝 SDK 元件、管理環境變數)。這份文件走一遍從零開始
到手機上看到 App 執行的完整流程,並列出常見的卡關狀況。

## 1. 安裝 Android Studio

到 https://developer.android.com/studio 下載目前的穩定版(Windows/macOS/Linux
都有)。安裝時用預設的 **Standard** 安裝類型即可——第一次啟動的 Setup Wizard
會自動幫你裝好:

- 一份內建的 JDK(不用另外裝,這點跟 VS Code 那條路不一樣)
- Android SDK Platform-tools(`adb` 等)
- 一份預設的 SDK Platform 與 build-tools

這些元件裝在哪、要不要換路徑,精靈畫面都會問,不確定就照預設值走。

## 2. 開啟專案

**開 `android/` 這個資料夾本身,不是整個 repo 根目錄。**
根目錄下沒有 `settings.gradle.kts`,Android Studio 認不出那是一個 Gradle 專案。

啟動畫面選 **Open**(或已經開著別的專案的話用 **File → Open**),選到
`delayCamForArchery/android/` 那一層,按確定。

## 3. 第一次 Gradle Sync

開啟後 Android Studio 會自動開始 sync,右下角會有進度條。第一次 sync 需要下載
這個專案用到的所有依賴(AndroxX、CameraX、MediaPipe、Media3 等),視網路狀況
可能要幾分鐘到十幾分鐘,正常現象,讓它跑完。

**可能會跳出的提示:**

- **「Android Gradle Plugin 版本較舊,是否升級」**——這個專案寫的版本
  (AGP 8.6.0 / Gradle 8.14.3,見 `build.gradle.kts` 和
  `gradle/wrapper/gradle-wrapper.properties`)是寫的當下已知穩定、彼此相容的
  組合;可以接受升級到 Studio 建議的版本,通常不會有問題,但如果升級後 sync
  反而失敗,回頭把 `build.gradle.kts` 裡的版本號改回原本的數字再試一次。
- **「Kotlin plugin 版本跟 Gradle 內建的不一致」**類的警告——同上,接受
  Studio 的建議通常最省事。

**Sync 失敗的常見原因:**

- **網路連不到 `google()` / `mavenCentral()`**——這個專案的依賴大多在 Google
  的 Maven repo(AndroidX/CameraX/Media3)和 Maven Central(MediaPipe、
  Kotlin 相關套件),兩邊都連不到的話 sync 一定會卡住或失敗,檢查網路/防火牆/
  VPN 設定。
- **JDK 版本不對**——`app/build.gradle.kts` 指定 JVM target 17,Android Studio
  內建的 JDK 通常沒問題;如果你手動在
  `Settings → Build, Execution, Deployment → Build Tools → Gradle` 裡改過
  「Gradle JDK」指到別的版本,確認那個版本是 17 以上。
- Sync 卡住不動、或報一些看起來跟這個專案完全無關的錯——先試
  `File → Invalidate Caches / Restart`,清掉 Studio 自己的索引快取重來一次。

## 4. 手勢辨識模型

`gesture_recognizer.task` 已經包在 `app/src/main/assets/` 裡了(見該資料夾
README),不用像很多 Android 專案那樣另外手動下載大型 asset 檔案。

## 5. 接上手機

沒有機型/廠牌限制,Android 10(API 29)以上的裝置都符合這個專案的 minSdk。

- **USB 偵錯**:手機「設定 → 關於手機」連點版本號 7 次開啟開發者選項,
  「開發人員選項 → USB 偵錯」打開,USB 線接電腦(確認線材支援傳輸資料,不是
  只能充電的線),手機上會跳授權提示,同意。
- **或無線偵錯**(Android 11+,不用接線):開發人員選項裡的「無線偵錯」,
  Android Studio 右上角的裝置下拉選單裡選 **Pair Devices Using Wi-Fi**,
  照畫面配對一次即可,之後同一個網路下會自動偵測到。

裝置成功連上的話,Android Studio 工具列的裝置下拉選單會列出它。

## 6. Run

裝置選好之後,直接按工具列的綠色三角形(Run ▶,或 `Shift+F10` /
`Ctrl+R`)。第一次會順便 build,完成後 App 會自動裝上手機並啟動。

## 7. 看 log

**View → Tool Windows → Logcat**,選到你的裝置與這個 App 的 process 之後,
可以直接在搜尋框篩選 tag。這個專案目前用到 `Log.e`/`Log.w` 的地方主要在
`MainActivity`、`DelayCamViewModel`、`GestureEngine`、
`ContinuousRecordingSession` 這幾個 tag,剛好都是本目錄 README 裡列為風險
最高的幾個檔案,實機測試出問題時優先看這幾個。

## 8. 疑難排解

- **「Incompatible Gradle JVM version」/「Gradle version 8.14.3 is
  incompatible with the Gradle JVM version 25」**:這個專案釘死用
  Gradle 8.14.3(見 `gradle/wrapper/gradle-wrapper.properties`),它最高只支援
  到 JDK 24,還沒支援到 JDK 25。Android Studio 有時候會抓到系統上裝的較新 JDK
  當預設值,跟 Gradle 版本兜不起來。點畫面上的
  **「Apply compatible Gradle JDK configuration and sync」**就會自動選一個
  相容的 JDK(通常是 Studio 自己內建的 17 或 21)來跑 Gradle,不用自己手動選;
  這只是改「用哪個 JDK 執行 Gradle」,不影響專案本身編譯用的 JVM target(17,
  見 `app/build.gradle.kts`)。手動選的話是
  `Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK`,
  選一個 17–24 之間的版本,不要選 25 或更新的。
- **`Failed to resolve: <某個套件座標>`**:這份骨架的依賴版本號是在完全連不到
  Google Maven(AndroidX/CameraX/MediaPipe/Media3 的套件都放在那)、也連不到
  Maven Central 的沙盒環境裡寫的,所以版本號、甚至座標本身都只能憑寫的當下的
  既有知識,沒辦法實際逐一驗證——遇到某個依賴解析不到是有可能的,不是你設定
  哪裡錯了。排查順序:
  1. 先確認那個依賴**有沒有在程式碼裡真的用到**(`grep -rn` 對應的
     `import` 路徑)。像 `media3-ui-compose` 這個例子,加進
     `app/build.gradle.kts` 但整個專案沒有任何地方 import 它,直接刪掉那行
     依賴就解決了,不用花時間去找正確版本號。
  2. 如果有用到:多半是版本號寫錯或該版本已下架。點 Android Studio 錯誤訊息
     旁邊的 **Show in Project Structure dialog**,在依賴那一頁通常可以直接
     選清單裡實際存在的版本;或直接去該套件的官方文件/release notes 查目前
     的最新穩定版,換掉 `build.gradle.kts` 裡寫的版本號。
  3. **「Fix with AI」那個按鈕本身沒問題可以點**,但看不到它實際改了什麼之前
     不要照單全收——尤其如果它把版本號降到明顯舊很多的版本,或改動的依賴跟
     錯誤訊息講的不是同一個,回頭確認一下改動內容。
- **`INSTALL_FAILED_...` 系列的安裝錯誤**:通常是手機上已經裝過舊版簽章不同,
  先 `adb uninstall com.archery.delaycam`(或在手機上手動移除 App)再重新
  Run 一次。
- **裝置下拉選單裡看不到手機**:確認 USB 偵錯真的開了、線材支援傳輸、手機上
  有沒有跳出「允許這台電腦進行偵錯」的提示但被忽略掉了(有時候會被通知列
  蓋住)。
- **Build 產物在哪**:`app/build/outputs/apk/debug/app-debug.apk`,想直接用
  `adb install` 裝或分享給別人測都可以從這裡拿。

## 不想用 Android Studio?

見 [`vscode-setup.md`](vscode-setup.md)——一樣可以 build/裝到手機,只是要自己
處理 SDK 命令列工具的安裝,而且沒有中斷點除錯、Logcat 視窗這些 Studio 內建的
工具,取捨說明都寫在那份文件裡。
