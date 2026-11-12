# OmniStream Player (Android 跨來源智慧背景播放器)

專為 Android 打造的跨來源音訊播放器，完美整合**手機本機檔案**、**Google 雲端硬碟**與 **YouTube 頻道更新**，支援**關閉螢幕背景播放**與**斷點續播記憶**。

---

## 🌟 核心特色

1. **關閉螢幕背景播放 (Background Playback)**
   - 基於 **Android Jetpack Media3 (`MediaSessionService` + `ExoPlayer`)**。
   - 具備前台通知欄控制器 (Media Notification) 與鎖定螢幕媒體控制。
   - 配置 `WAKE_MODE_NETWORK` 與 AudioFocus 音訊焦點管理，鎖定螢幕依然穩定播放。

2. **Google 雲端硬碟自動偵測與連續串流 (GDrive Auto-Sync & Streaming)**
   - 透過 `HttpDataSource` 注入 Bearer Token 進行無縫 Range 串流，結合 ExoPlayer `SimpleCache` (512MB LRU)，邊播邊暫存，**無需預先下載整首歌曲**。
   - Android `WorkManager` 背景每 4 小時自動輪詢指定資料夾，每天新增內容主動加入播放清單。

3. **YouTube 頻道更新追蹤與純音訊抽取 (YouTube Audio Extractor)**
   - 支援免 API Key 的頻道 RSS Feed 輪詢，即時掌握追蹤的 YouTuber 發片動態。
   - 純音訊抽取引擎（解析最高音質 M4A/Opus 直接串流），省電、省流量、無廣告。

4. **跨來源混合播放清單 (Unified Multi-Source Playlist)**
   - 本機、雲端、YouTube 在同一播放隊列內無縫切換（Gapless playback）。
   - 每首曲目皆標記清晰來源 Badge（`[本機]`、`[雲端]`、`[YouTube]`）。

5. **斷點續播記憶 (Resume Playback)**
   - 透過 Room Database 持久化上次播放的曲目 ID 與毫秒級進度。
   - 下次開啟 App，自動恢復播放清單與中斷點，直接往下連續播放。

---

## 🛠️ 技術棧規格

- **語言**：Kotlin 1.9.23
- **介面**：Jetpack Compose (Material 3 + 霸王色 Tactical HUD 暗黑主題)
- **音訊引擎**：Jetpack Media3 ExoPlayer 1.3.1
- **資料庫**：AndroidX Room 2.6.1
- **背景任務**：AndroidX WorkManager 2.9.0
- **網路**：OkHttp 4.12.0

---

## 📱 編譯與打包方式

1. 使用 **Android Studio** (Hedgehog / Iguana / Jellyfish 或更新版本) 開啟 `F:\_proj\000_overlordstudio\D_PRO\OmniStreamPlayer`。
2. 點擊 `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`。
3. 將產出的 `app-debug.apk` 傳送至 Android 手機安裝即可使用！
