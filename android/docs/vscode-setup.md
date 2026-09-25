# 用 VS Code 開發 / 建置 / 跑到實機(不裝 Android Studio)

Android Studio 本質上是「IntelliJ + Android 外掛 + SDK Manager 的 GUI」——真正
做事的是 **Gradle**(這個專案已經有 `./gradlew`,是用真的 `gradle wrapper` 指令
產生的,不是空殼)和 **Android SDK**(`adb`、`build-tools` 等命令列工具),兩者
都獨立於 IDE。所以可以整個流程都在 VS Code + 終端機裡完成,只是體驗上有幾個
明顯的落差,見文末「跟 Android Studio 的差異」。

## 1. 裝 JDK 17

Android Gradle Plugin 8.6(見 `app/build.gradle.kts`)需要 JDK 17。裝
[Temurin 17](https://adoptium.net/) 或任何 OpenJDK 17 發行版都可以,裝完設定
`JAVA_HOME` 指過去。

## 2. 裝 Android SDK 命令列工具(不用整個 Android Studio)

1. 到 https://developer.android.com/studio#command-tools 下載
   「Command line tools only」給你的作業系統的那個 zip
   (`commandlinetools-<os>-<version>_latest.zip`)。
2. 解壓到一個固定位置,**注意目錄結構**——`sdkmanager` 期待的路徑是
   `<SDK 根目錄>/cmdline-tools/latest/bin/sdkmanager`,所以解壓出來的
   `cmdline-tools` 資料夾要整個改名成 `latest` 再塞進去,不能直接把 zip
   內容丟進 `cmdline-tools/`。例如:

   ```bash
   mkdir -p ~/Android/sdk/cmdline-tools
   unzip commandlinetools-*.zip -d ~/Android/sdk/cmdline-tools
   mv ~/Android/sdk/cmdline-tools/cmdline-tools ~/Android/sdk/cmdline-tools/latest
   ```

3. 設定環境變數(寫進 `~/.zshrc` / `~/.bashrc` 或 Windows 的環境變數設定):

   ```bash
   export ANDROID_HOME="$HOME/Android/sdk"
   export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
   ```

4. 用 `sdkmanager` 裝這個專案需要的套件、同意授權條款:

   ```bash
   sdkmanager --licenses          # 全部按 y
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

   版本要跟 `app/build.gradle.kts` 裡的 `compileSdk` / `targetSdk`(目前是 34)
   對得上;之後如果 Android Studio 提示升級 AGP 順便把 compileSdk 也調高了,
   這裡也要跟著補裝對應版本。

## 3. VS Code 外掛

- **Kotlin**(`fwcd.kotlin`)— 語法高亮、基本補全與診斷,底層跑
  kotlin-language-server,第一次開專案索引會比較慢,屬正常現象。
- **Gradle for Java**(`vscjava.vscode-gradle`)— 側邊欄可以直接點 Gradle
  tasks(`assembleDebug`、`installDebug` 等),不用每次都手動打指令。
- (可選)**XML**(`redhat.vscode-xml`)— 編輯 `res/*.xml`(manifest、drawable、
  themes)時的語法檢查比較舒服。

這個 repo 的 `android/.vscode/extensions.json` 已經列了以上建議清單,VS Code
開啟 `android/` 資料夾時應該會自動跳出「要不要安裝建議的外掛」提示。

## 4. 接上你的 Android 手機

沒有指定機型/廠牌的要求,只要是 Android 10(API 29)以上的裝置就符合這個專案
的 minSdk。

- **USB 偵錯**:手機「設定 → 關於手機」連點版本號 7 次開啟開發者選項,
  「開發人員選項 → USB 偵錯」打開,USB 接電腦,手機上會跳授權提示,同意。
  `adb devices` 應該要列出裝置。
- **或無線偵錯**(Android 11+,不用接線):開發人員選項裡的「無線偵錯」,
  依畫面上的配對碼在電腦端 `adb pair <ip>:<port>` 配對一次即可。

## 5. Build 並安裝到手機

在 `android/` 目錄下(不是 repo 根目錄):

```bash
./gradlew installDebug
```

第一次執行會下載一堆 Gradle/AndroidX/CameraX/MediaPipe 依賴,需要正常的網路
連線,可能要幾分鐘。成功的話手機上會直接出現這個 App,不需要再手動點安裝。

看 log(這個專案目前用到 `Log.e`/`Log.w` 的地方主要在 `MainActivity`、
`DelayCamViewModel`、`GestureEngine`、`ContinuousRecordingSession` 這幾個 tag,
剛好都是 README 裡列為風險最高的幾個檔案):

```bash
adb logcat -s MainActivity:* DelayCamViewModel:* GestureEngine:* ContinuousRecordingSession:*
```

或直接看這個 App 的所有 log(先跑起來再執行,抓目前的 pid):

```bash
adb logcat --pid=$(adb shell pidof -s com.archery.delaycam)
```

`android/.vscode/tasks.json` 裡已經把 build / install / uninstall / clean
包成 VS Code tasks(`Cmd/Ctrl+Shift+P` → `Tasks: Run Task`),不用每次都手打。

## 跟 Android Studio 的差異(取捨要知道的事)

- **沒有好用的中斷點除錯**。Android Studio 的 debugger 是跟 IDE 深度整合的;
  VS Code 這條路線基本上沒有可靠的 Kotlin/Android 斷點除錯可用,實務上要靠
  `Log.d`/`adb logcat` 加印訊息去除錯,而不是下中斷點單步執行。
- **沒有 Compose Preview**(即時預覽畫面長怎樣)。要看 UI 實際效果就是
  `installDebug` 裝到手機上直接看。
- **補全/重構能力明顯弱於 Android Studio**——kotlin-language-server 沒有
  IntelliJ 那套完整的語意分析,大型專案的補全/跳轉有時候會不準或很慢。
- 沒有內建的 **Logcat 視窗、Layout Inspector、Profiler** 這些 Android Studio
  的除錯工具,都要用命令列 `adb` 手動做對應的事。

如果之後覺得這些落差太麻煩,隨時可以把同一個 `android/` 資料夾直接用 Android
Studio 開起來——兩邊共用同一份 Gradle 專案,不會互相干擾,步驟見
[`android-studio-setup.md`](android-studio-setup.md)。
