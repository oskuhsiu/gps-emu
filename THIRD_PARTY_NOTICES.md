# Third-party notices

自有模擬定位、行程引擎與 UI 程式依官方 API 獨立撰寫；未複製 OSS 候選的實作。專案本身的發布授權尚未由擁有者指定；此檔不是對自有程式碼授予開源授權。

- **Leaflet 1.9.4** — BSD-2-Clause。[原始授權全文](app/src/main/assets/leaflet/LICENSE)隨 APK assets 保留。JavaScript／CSS SHA-256 已與[官方下載頁](https://leafletjs.com/download.html)的 SRI 值比對。
- **OpenStreetMap 資料** — © OpenStreetMap contributors，ODbL；[版權與授權](https://www.openstreetmap.org/copyright)。圖磚另依 [OSMF 使用政策](https://operations.osmfoundation.org/policies/tiles/)；App 顯示 attribution，不大量預抓。
- **FOSSGIS / OSRM 公開路由服務** — 使用 HTTP 服務，未將 OSRM backend 程式碼編入 App；遵守[服務政策](https://routing.openstreetmap.de/about.html)。路線源自 OSM。
- **Google Play services Location 21.3.0** — 官方 Google SDK，並非本專案開源自有程式。[Google APIs 使用條款](https://developers.google.com/terms)與 SDK 附帶授權適用；其 transitive 依賴由 Gradle 解析。
- **desugar_jdk_libs 2.1.5** — Google 提供的 Java 標準函式庫相容支援，讓 Android 9 可執行目前使用的較新 Java API。參見[上游原始碼與授權](https://github.com/google/desugar_jdk_libs)、[版本紀錄](https://github.com/google/desugar_jdk_libs/blob/master/CHANGELOG.md)及 [Android 相容 API 清單](https://developer.android.com/studio/write/java11-default-support-table)。
- **AndroidX、Kotlin／coroutines、Google Play services transitive 依賴** — 來自固定頂層依賴解析，分別保留 Maven artifact 附帶的 LICENSE／NOTICE；可用 Gradle `:app:dependencies --configuration debugRuntimeClasspath` 查看完整樹。不要把「自有版本」解讀為沒有第三方 SDK。
- **JUnit 4.13.2** — 測試依賴，EPL-1.0；不打包到產品 APK。
- **Gradle Wrapper 8.13** — Apache-2.0；只用於建置，wrapper script 保留版權聲明。

此清單說明直接使用的主要元件，不代表已完成全面漏洞或商用發行授權稽核。完整相依版本與授權仍應以建置解析結果及各上游附帶文件為準。
