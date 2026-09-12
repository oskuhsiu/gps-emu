# Third-party notices

自有模擬定位、行程引擎與 UI 程式依官方 API 獨立撰寫；未複製 OSS 候選的實作。專案本身的發布授權尚未由擁有者指定；此檔不是對自有程式碼授予開源授權。

- **Leaflet 1.9.4** — BSD-2-Clause。[原始授權全文](app/src/main/assets/leaflet/LICENSE)隨 APK assets 保留。JavaScript／CSS SHA-256 已與[官方下載頁](https://leafletjs.com/download.html)的 SRI 值比對。
- **OpenStreetMap 資料** — © OpenStreetMap contributors，ODbL；[版權與授權](https://www.openstreetmap.org/copyright)。圖磚另依 [OSMF 使用政策](https://operations.osmfoundation.org/policies/tiles/)；App 顯示 attribution，不大量預抓。
- **FOSSGIS / OSRM 公開路由服務** — 使用 HTTP 服務，未將 OSRM backend 程式碼編入 App；遵守[服務政策](https://routing.openstreetmap.de/about.html)。路線源自 OSM。
- **Google Play services Location 21.3.0** — 官方 Google SDK，並非本專案開源自有程式。[Google APIs 使用條款](https://developers.google.com/terms)與 SDK 附帶授權適用；其 transitive 依賴由 Gradle 解析。
- **AndroidX、Kotlin／coroutines、Google Play services transitive 依賴** — 來自固定頂層依賴解析，分別保留 Maven artifact 附帶的 LICENSE／NOTICE；可用 Gradle `:app:dependencies --configuration debugRuntimeClasspath` 查看完整樹。不要把「自有版本」解讀為沒有第三方 SDK。
- **JUnit 4.13.2** — 測試依賴，EPL-1.0；不打包到產品 APK。
- **Gradle Wrapper 8.13** — Apache-2.0；只用於建置，wrapper script 保留版權聲明。

2026-09-12 的[第三方來源與安全檢查](docs/security/2026-09-12/README.md)已核對 Leaflet、Gradle wrapper 與 28 個產品 runtime 依賴的官方來源，並保存 OSV 查詢及 Leaflet CVE-2025-69993 的本案適用性判斷。此次仍未完成全面 CVE／授權稽核；檔案雜湊一致與成功建置不代表全部依賴零漏洞或已完成商用發布審核。
