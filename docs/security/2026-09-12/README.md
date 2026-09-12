# Route Mock 第三方來源與安全檢查

檢查日期：2026-09-12（Asia/Taipei）。目標是確認參考 OSS 時是否引入惡意程式碼，以及目前 App 是否存在可疑的資料傳輸、權限或執行路徑。

**在本次檢查範圍內，未發現引入惡意程式碼的證據。這是原始碼審查、官方檔案比對與已知漏洞查詢的結果，不能解讀為保證沒有惡意行為或未知漏洞。** 本輪只新增檢查紀錄與更新第三方說明，沒有修改產品或建置行為。

## 1. 範圍與可追溯性

- 審查 `app`、`core` 自有 Java、地圖 HTML/JavaScript、manifest、資料備份規則、Gradle 設定與 release workflow；另外檢查 `probe`／本機工具與產品 APK 的邊界。
- 本機 `main` 尚無 commit，因此本次對象是工作目錄，而非某個已發布版本。[source-snapshot.json](source-snapshot.json) 保存相關檔案 SHA-256。
- 實際解析 `:app:releaseRuntimeClasspath`，得到自有 `core` 與 **28 個第三方 JAR/AAR**；保留[完整依賴樹](release-runtime-dependencies.txt)。
- 檢查既有 debug APK 及 release unsigned APK。後者是前次 CI 驗證用的 `versionName=1.2.3`／`versionCode=1002004`，不是已簽章發布的版本。兩者路徑、完整 SHA-256 與大小見 [apk-inspection.json](apk-inspection.json)。
- 未上傳 APK／原始碼到外部掃毒服務。對外查詢使用公開套件名稱、版本與官方下載 URL。

## 2. 是否把參考專案的程式碼放進 App

目前 [settings.gradle](../../../settings.gradle) 只有 `app`、`core`、`probe`；[app/build.gradle](../../../app/build.gradle) 的產品依賴是自有 `core` 與 `com.google.android.gms:play-services-location:21.3.0`。

沒有候選 mock GPS 專案的 Gradle module、Maven 依賴、JitPack URL、預編譯 APK/JAR 或 native library。自有定位與路線程式也未見候選專案的 package/import。這與[原始 OSS 決策](../../research/oss-scout.md)及[第三方說明](../../../THIRD_PARTY_NOTICES.md)所記錄的獨立實作一致；本次沒有對所有候選原始碼執行逐行相似度鑑定，不能用 package 名稱證明程式碼創作歷史。

實際重用的程式包含 Leaflet、Google Play services、AndroidX、Kotlin 標準函式庫及 coroutines；因此不能宣稱 App 沒有第三方程式碼。

## 3. 第三方檔案與已知漏洞

| 檢查 | 結果 | 證據與限制 |
| --- | --- | --- |
| 28 個實際解析的第三方 runtime JAR/AAR | **28/28 SHA-256 相符** | 從 Google Maven 或 Maven Central HTTPS 重新取得同版本完整檔案，與本機建置快取比較；[dependencies.json](dependencies.json) 記錄 URL 與雙方雜湊。這證明相同位元組，不證明上游程式無漏洞。 |
| Leaflet 1.9.4 JS/CSS/LICENSE | **與官方發行檔案相符** | JS/CSS 符合[官方下載頁](https://leafletjs.com/download.html) SRI；三檔均與官方 npm 發行包一致，LICENSE 亦與 upstream tag 相符；[來源紀錄](provenance.json)。 |
| Gradle wrapper | **JAR 與官方檔案相符；腳本有已審查差異** | JAR SHA-256、設定中的 distribution SHA-256 均符合官方值；腳本差異見下方，未聲稱兩個腳本原封不動。 |
| OSV 版本查詢 | **29 個查詢均未回傳漏洞紀錄，但人工查到一筆 Leaflet 公告** | 28 個 Maven artifact 版本與 `npm:leaflet@1.9.4`；保存[請求](osv-request.json)及[回應](osv-response.json)。未列入資料庫、尚未公開或套件映射缺漏的漏洞仍可能存在；沒有掃描完整建置工具依賴。 |
| APK assets | debug／release 的 **6 個 assets 均與工作目錄相符** | 包含 Leaflet JS/CSS/LICENSE 及自有 map 檔案；[APK 紀錄](apk-inspection.json)。 |
| APK DEX 類別來源 | release 3,863 類別中，3,722 可對應解析出的 JAR/AAR／core 類別名稱；其餘 141 符合自有或建置生成類別模式 | 無未歸類名稱；這是名稱歸屬分析，沒有證明所有方法位元組與來源等價。debug 結果另記錄於 JSON。 |
| 額外二進位 | 未見 `.so`、內嵌 APK/JAR 或 `dev.routemock.probe` 類別 | `DebugProbesKt.bin` 確實存在，已逐位元組確認來自官方 `kotlinx-coroutines-core-jvm:1.7.3`。不是本案 Route Probe；[來源紀錄](sdk-resource-attribution.json)。 |

OSV 查詢方式依[官方 API 文件](https://google.github.io/osv.dev/api/)；上述「沒有紀錄」只描述這次 API 回應，並非完整 CVE 稽核或惡意程式偵測引擎結果。

人工交叉查詢找到 **CVE-2025-69993 / GHSA-h5cx-hfj5-x8v3**。[GitHub Advisory Database 紀錄](https://github.com/advisories/GHSA-h5cx-hfj5-x8v3)為 Unreviewed、CVSS 6.1，敘述 Leaflet ≤1.9.4 的 `bindPopup()` 會將未清理的攻擊者字串渲染成 HTML；該紀錄沒有 package mapping，也不能觸發 Dependabot alert，顯示單靠套件版本查詢確有涵蓋缺口。[Leaflet 維護者聲明](https://github.com/Leaflet/Leaflet/issues/10214)則指出這是有文件記載的 HTML API 行為，已向 MITRE 提出爭議。

**本案適用性判斷：目前原始碼未見這筆公告的觸發路徑。** 自有 `map.js` 沒有呼叫 popup／tooltip／`setContent` API；`L.divIcon` 的 HTML 只來自 `String(i+1)`，attribution 為固定字串，路由回應只轉成驗證過的數字座標。這項結論來自本案資料流審查，並非因維護者爭議而直接忽略公告；也未做動態 XSS 攻擊驗證。若將來新增地點名稱、路線匯入、popup 或 tooltip，應使用 DOM `textContent` 或經適當清理的 HTML 重新審查。

主 agent 另重新取得 upstream `v8.13.0` 的 `gradlew`／`gradlew.bat` 並逐行比對：[差異紀錄](wrapper-script-diffs.json)顯示正規化換行後，每檔只少一個 `-Dfile.encoding=UTF-8` 預設 JVM 選項，batch 額外有 CRLF／LF 差異。沒有增加指令、主機或下載路徑；判斷這些差異本身沒有顯示惡意植入，因此本次未改寫 wrapper。完整來源查核與公告日期見 [provenance.md](provenance.md)。

APK 內另有 `googleapis.com/auth/drive`、`userinfo.email` 等字串，已追溯到 Google SDK 的 `Scopes.class` 常數；`plus.google.com` 位於同一官方 SDK 的類別。自有程式沒有呼叫登入、讀取帳號或 Drive API。字串存在不能直接等同於對外連線或竊取資料；本次亦未用封包側錄確認 SDK 全部執行期行為。

## 4. 敏感操作與資料流

| 區域 | 審查結果與位置 |
| --- | --- |
| 指令執行／更新載入 | 自有 `app`／`core` 未見 shell/root 指令、`Runtime.exec`、`ProcessBuilder`、自行載入 DEX/native library、下載 APK 或遠端更新執行路徑。標準 SDK 與裝置服務的內部實作不在此「未見」聲明範圍。 |
| 權限與元件 | [APK 實際 manifest](release-apk-manifest.txt) 有網路、位置、mock location、前景服務、通知與 wake lock，沒有通訊錄、簡訊、麥克風、相機、套件安裝、全檔案讀取、開機自啟或無障礙服務。唯一 exported 元件是啟動畫面；mock service 與 GoogleApiActivity 均不對外公開。 |
| 外部 Intent | `MainActivity` 沒有從外部 Intent 讀取路線、URL 或啟動指令。Service 接收 explicit Intent；通知的 PendingIntent 均為 immutable，只有開啟畫面及停止。 |
| 真實位置讀取 | 產品自有程式使用 mock 輸出 API，未見 `getCurrentLocation`、`getLastLocation` 或訂閱真實位置更新。獨立測試 App `probe` 才會讀取並將位置記錄到 Logcat，且未打包到產品 APK。 |
| WebView | `MainActivity.java:123–157` 關閉 file/content access、Web geolocation、mixed content；只載入 bundled JS；[CSP](../../../app/src/main/assets/map.html) 禁止外部 script、frame、fetch。Bridge 只暴露 `addPoint(double,double)`，座標經 `GeoPoint` 檢查有限值與範圍，不能直接開始模擬或讀檔。 |
| 注入與網路回應 | `WalkingRouter.java:26–79` 只使用固定 HTTPS 主機，禁止 redirect，設定 timeout、2 MiB 回應／10,000 點上限，解析 JSON 數字並驗證座標；沒有把路由回應當作 HTML 或程式執行。`map.js` 的 marker HTML 只有本機產生的序號。 |
| 本機儲存 | 草稿存於 App 私有 `files/draft.json`；cleanup 紀錄使用 private preferences。停用 backup，並排除 cloud backup／device transfer。產品自有程式未見座標寫入 Logcat。 |
| release workflow | 四個 actions 固定完整 commit SHA；tag 先驗格式，shell 使用 quoted env 值；發布權限放在另一 job。簽章 secret 未傳給 Gradle 執行步驟；keystore 以限制權限的暫存檔使用並在結束刪除。這不構成對受入侵 runner／惡意 build plugin 的隔離保證。 |

WebView bridge 的檢查依據包含 [Android 官方安全說明](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)。本案的 bundle-only script 與禁止 frame 限縮了 bridge 的暴露面，但 Android System WebView 自身仍需更新，不能由 Java 設定排除引擎漏洞。

可由自有原始碼確認的外部資料流如下：

| 目的地 | 何時／傳送什麼 |
| --- | --- |
| `routing.openstreetmap.de` | 使用者按「規劃步行」時，途經點經緯度放在 HTTPS URL，並傳送 User-Agent。服務端會知道請求來源 IP；服務政策明確記錄路由請求會存入 log。 |
| `tile.openstreetmap.org` | 地圖顯示／平移／縮放時請求 `z/x/y.png` 圖磚。供應商會收到來源 IP、User-Agent 與圖磚範圍；範圍可反映使用者正在看的區域。 |
| `www.openstreetmap.org` | 點擊授權或修正地圖連結時交給外部瀏覽器。 |
| 裝置上的 Google Play services | App 呼叫 fused mock API。第三方 SDK、Google 系統服務、裝置設定及其可能的對外行為，未以本輪靜態檢查完整驗證。 |

`https://routemock.local/` 是 WebView 攔截後回傳 assets 的虛擬來源，不是本案自建的遠端伺服器。路由服務紀錄政策見 [FOSSGIS 說明](https://routing.openstreetmap.de/about.html)。這些必要外部服務使 App 並非全程離線；目前 UI 已揭露路由會傳送選點。

## 5. 尚待補強與本次限制

**供應鏈防護缺口：尚未設定 Gradle dependency verification 或 dependency locking。** 目前頂層版本固定，且本次實際 artifact 全數與官方來源一致，但未來重新下載時，建置沒有使用本案維護的預期雜湊強制拒絕變造依賴。建議下一步把人工核對後的 runtime、plugin、建置與測試依賴納入驗證，並用一份故意變造的測試 artifact 驗證建置確實失敗；不能只盲目採信現有快取產生清單。[Gradle 官方文件](https://docs.gradle.org/current/userguide/dependency_verification.html) 說明雜湊／簽章驗證，以及首次產生可信清單的限制。本次只記錄此缺口，未改動 CI 或依賴解析。

其餘界線：

- 沒有對 Google 閉源 SDK、Android／WebView／GMS 系統服務做完整反編譯與程式碼稽核。
- 沒有驗證整套 JDK、Android SDK、AGP 與所有 build plugin 間接依賴；不能把 runtime 的 28/28 比對套用到整條建置供應鏈。
- 沒有啟動裝置進行惡意輸入測試、TLS 代理／封包側錄、動態沙箱分析或多引擎掃毒；先前的功能測試不算本輪安全測試。
- 官方來源雜湊一致無法排除上游本身遭入侵；OSV 查不到也無法排除未公開問題。
- debug APK 可除錯，是開發產物；release unsigned APK 的 manifest 沒有 debuggable=true，但尚未成為正式簽章交付物。不能以其中一份的結果概括未來不同雜湊的 APK。

本次沒有取得需要立即移除或隔離某個第三方套件的證據；後續若有套件／版本／來源異動，應重新執行上述查核。
