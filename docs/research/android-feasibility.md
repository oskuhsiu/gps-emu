# Android 15+ 模擬定位與步行路線可行性查證

查閱日期：2026-09-12（Asia/Taipei）

本文件只整理 Android、Google Play services、Google Maps Platform、OpenStreetMap Foundation、Project OSRM、MapLibre 與遊戲發行商公開的官方文件。沒有讀取第三方 mock GPS 實作，也沒有在實機、模擬器或公開路由端點執行請求；因此本文不把任何 API 行為、遊戲相容性或服務可用性描述成已通過的 runtime 驗證。

「能影響其他 App」在 Android 公開能力的語境中，指其他 App 從 Android `LocationManager` 或 Google Fused Location Provider 取得到被標記為 mock 的位置。接收端仍可辨識並拒絕 mock；這不等於所有定位來源、所有 App 或特定遊戲都會接受。

## 1. 查證結論

- Android 提供正式的 mock location 機制。使用者必須在開發人員選項指定 mock location App；`LocationManager` test provider 與 Fused Location Provider mock mode 都受這個系統授權／AppOps 門檻保護。這條路不需要 root，也不應以修改系統、隱藏 mock 標記或繞過接收端檢查為前提。
- `LocationManager` 的 test provider 可以把位置送到使用 `LocationManager` 的接收端，且 Android 31 以上可用 `Location.isMock()` 識別。Google 的 Fused mock mode 影響所有使用 FLP 的 location clients，包括其他 process 與 geofencing 等衍生 API；它不代表所有非 FLP 的定位來源都會被改寫。
- Fused API 的順序是先成功進入 mock mode，再逐點送入時間單調遞增的 `Location`；結束時必須明確退出 mock mode。`setMockMode(false)` 會清除 FLP 的 mock cache，官方也要求使用者端在完成後恢復正常狀態。
- 長時間、螢幕關閉後仍要輸出模擬行程，Android 的公開背景執行模型是使用可見通知的 `location` foreground service。Android 15 文件所述的 6 小時／24 小時限制只列於 `dataSync` 與 `mediaProcessing` 類型；文件沒有把相同限制列到 `location` 類型。Android 16 另要求從 foreground service 啟動的 JobScheduler／WorkManager 等工作遵守工作配額。
- Wake lock 不是模擬定位 API 的必要前置條件。Android 文件建議先不用 wake lock；只有裝置進入 suspend 會破壞使用者可見的長時間工作時才考慮，並應取得 `WAKE_LOCK`、以最小範圍使用、盡快釋放。本文沒有找到 Android 15／16 對 location wake lock 的額外固定時限；這不構成 OEM 省電行為的保證。
- Pokémon GO 官方說明明確指出 mock locations 可能導致「failed to detect location」，並要求關閉 mock location app；其條款也將 falsifying location、emulator、modified/unofficial software 與第三方軟體列為可採取處分的行為。Pikmin Bloom 官方支援頁同樣把 spoofing 列為可能造成精確位置驗證錯誤且違反條款的情況。因此「系統可送出 mock」與「遊戲會接受並正常遊玩」必須分開驗證，不能承諾遊戲相容性。
- Google Routes API 的 `WALK` 路線可取得 polyline，但官方要求 API key 或 OAuth、專案開啟 billing；步行路線仍是 beta，且需要向使用者顯示路線可能缺少清楚人行道／步道的警告。Google 條款禁止把 Routes API 內容與非 Google map 合用，且對 Routes API 的經緯度快取有 30 天限制。這不是無密鑰的初始路線來源。
- 不需 secret 的路線輸入可以是使用者自己提供的 GPX／GeoJSON／polyline 或座標序列；這不會自動產生步行路網路線。公開 `routing.openstreetmap.de/routed-foot`／OSRM 服務可作為無 API key 的外部候選；官方 About 頁明列 request logging、FOSSGIS 隱私說明連結與使用政策（attribution、fix-the-map、有效 User-Agent／適用時的 Referer、每秒最多一請求、禁止 scraping／heavy usage）。該頁仍沒有對本 App 提供 SLA 或可用率承諾；需要可控可用性時，OSRM 官方文件也描述自架流程，但需自行維護 OSM 資料與路由服務。

## 2. Android 系統模擬定位 API

### 2.1 使用者授權與可見的 mock 性質

Android Studio 的官方開發人員選項文件把 **Select mock location app** 定義為用來 fake GPS location、測試 App 在其他地點的行為。這表示一般安裝的 App 要先由使用者在系統設定選為 mock location App，不能只靠 manifest 宣告就取得全域輸入權限。

來源：

- [Configure on-device developer options — Select mock location app](https://developer.android.com/studio/debug/dev-options#general)（Android Developers，頁面顯示最後更新 2026-09-01；查閱 2026-09-12）
- [AppOpsManager — OPSTR_MOCK_LOCATION](https://developer.android.com/reference/android/app/AppOpsManager#OPSTR_MOCK_LOCATION)（Android API reference，查閱 2026-09-12）

`LocationManager` 的 test-provider 方法若 mock location AppOp 沒有被允許，官方 API reference 指定會拋出 `SecurityException`。因此 UI 可以引導使用者前往設定，但不能替使用者修改安全設定。

### 2.2 `LocationManager` test provider

Android API reference 的公開操作語意如下：

1. `addTestProvider(provider, ProviderProperties)`（API 31）建立 test provider；同名 provider 會被替換。舊的參數多的 overload 從 API 3 就存在。
2. `setTestProviderEnabled(provider, true)` 啟用 test provider。
3. `setTestProviderLocation(provider, location)` 對該 provider 送出新位置。官方明確表示這個位置會對所有 clients 可識別為 mock，且 `Location.isMock()` 會是 true（API 31 起）。位置欄位不足會拋出 `IllegalArgumentException`。
4. 行程結束可用 `setTestProviderEnabled(provider, false)` 停用，再用 `removeTestProvider(provider)` 移除。`clearTestProviderLocation()` 在 API 29 已 deprecated 且官方說明一直是 no-op，不能把它當成清理機制。

這組 API 的「可影響範圍」是以該 provider 與 `LocationManager` 接收端為界。官方文件沒有保證每一個 App 都使用同一 provider，也沒有說明可以覆蓋 Wi-Fi、蜂巢式定位、GNSS measurement 或其他獨立感測資料。若 provider 名稱與既有 provider 相同，API reference 明確寫出建立時會替換既有 provider，所以 provider 命名、停用與移除的失敗狀態都必須被記錄。

來源：

- [LocationManager — addTestProvider](https://developer.android.com/reference/android/location/LocationManager#addTestProvider(java.lang.String,android.location.provider.ProviderProperties))（Android API reference，查閱 2026-09-12）
- [LocationManager — setTestProviderEnabled / setTestProviderLocation](https://developer.android.com/reference/android/location/LocationManager#setTestProviderLocation(java.lang.String,android.location.Location))（Android API reference，查閱 2026-09-12）
- [LocationManager — removeTestProvider](https://developer.android.com/reference/android/location/LocationManager#removeTestProvider(java.lang.String))（Android API reference，查閱 2026-09-12）
- [Location — isMock](https://developer.android.com/reference/android/location/Location#isMock())（Android API reference，查閱 2026-09-12）

### 2.3 Google Play services `FusedLocationProviderClient`

官方 Fused API reference 對 mock mode 的描述比單一 test provider 更廣：

- `setMockMode(true)` 會清除 FLP cache，之後 FLP 只回報由 `setMockLocation(Location)` 送入的位置。
- `setMockLocation(location)` 只能在 mock mode 成功後使用；正常 FLP 邏輯仍會套用。官方特別提醒時間戳應適當，因為 FLP 可能要求時間單調遞增。回報給 FLP clients 時，位置會標記為 mock，Android S 以上可用 `Location.isMock()`，更舊版本可用相容函式。
- mock mode 會影響所有使用 FLP 的 location clients，包括其他 process 與 geofencing 等衍生 API。因此它是裝置上 FLP 使用者共用的狀態，不是只影響本 App 的私有資料；接收端仍可用 mock 標記拒絕。
- `setMockMode(false)` 會清除 FLP cache 中的 mock locations 並回到正常狀態。官方要求使用完畢一定要恢復 false。Google Play services reference 也要求 Android M 以上的 client 具備 `android.permission.ACCESS_MOCK_LOCATION` 並在開發人員選項被選為 mock location app。

實作順序中，只有 API reference 明確要求的先後才是平台契約：

```text
mock app 已被使用者選取
        ↓
setMockMode(true) 成功
        ↓
逐點 setMockLocation，時間戳保持遞增
        ↓
停止逐點輸出
        ↓
setMockMode(false) 完成，確認 Task 結果
```

上圖的錯誤處理不是平台自動替代：如果 process 被殺、Task 失敗或服務被強制停止，下一次啟動時仍應檢查並提供清理狀態；不能把「停止按鈕被按下」當成已經恢復真實位置的證據。接收端何時取得下一個真實 fix 由裝置與接收端決定。

來源：

- [FusedLocationProviderClient — setMockMode](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient#setMockMode(boolean))（Google Play services reference，頁面顯示最後更新 2026-06-26；查閱 2026-09-12）
- [FusedLocationProviderClient — setMockLocation](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient#setMockLocation(android.location.Location))（Google Play services reference，頁面顯示最後更新 2026-06-26；查閱 2026-09-12）
- [Location — isMock](https://developer.android.com/reference/android/location/Location#isMock())（Android API reference，查閱 2026-09-12）

### 2.4 跨 App 的實際邊界

可查證的跨 App 關係是：

```text
使用者選定 mock App
        ↓
Android LocationManager test provider ──→ 使用 LocationManager 的 clients
        │                                  （位置可辨識為 mock）
        └─ 或
          Google FLP mock mode ──────────→ 所有 FLP clients／衍生 geofence clients
                                           （位置可辨識為 mock）
```

這不能推導出以下未被官方 API 保證的事情：

- 每一款 App 一定走 FLP，而不是直接走 `LocationManager`、自己的感測融合或伺服器驗證。
- App 一定接受標記為 mock 的位置。
- mock 位置會改變步數、陀螺儀、GNSS measurement、IP／Wi-Fi、Play Integrity 或遊戲後端判斷。
- 停止 mock 後所有接收端立即收到真實位置。

## 3. Android 15／16 長時間輸出、權限與電源

### 3.1 Location foreground service

若模擬行程在 Activity 不可見時仍要逐點輸出，Android 文件對 location foreground service 的要求包括：

- manifest 的 service 宣告 `android:foregroundServiceType="location"`。
- manifest 宣告 `FOREGROUND_SERVICE`，並在 target API 34 以上宣告相應的 `FOREGROUND_SERVICE_LOCATION`；API reference 將後者定義為允許一般 App 以 `location` 類型呼叫 `Service.startForeground()`。
- 裝置位置服務已開啟，且 App 已取得 `ACCESS_COARSE_LOCATION` 或 `ACCESS_FINE_LOCATION`。Android 12 以上使用者可只給 approximate location；若功能真的需要精確位置，必須在同一個 runtime request 同時請求 coarse 與 fine，並處理使用者只給 approximate 的結果。
- 先由使用者可見 Activity 啟動服務，再由服務呼叫 `ServiceCompat.startForeground()` 並顯示通知。target Android 12 以上的 App 一般不能從背景啟動 FGS；target Android 14 以上若 location 使用 while-in-use 權限，從背景建立 location FGS 會在建立時被拒絕，除非符合官方列出的例外／具備 `ACCESS_BACKGROUND_LOCATION` 的適用條件。

來源：

- [Foreground service types — Location](https://developer.android.com/develop/background-work/services/fgs/service-types#location)（Android Developers，頁面最後更新 2026-09-01；查閱 2026-09-12）
- [Declare foreground services and request permissions](https://developer.android.com/develop/background-work/services/fgs/declare)（Android Developers，查閱 2026-09-12）
- [Launch a foreground service](https://developer.android.com/develop/background-work/services/fgs/launch)（Android Developers，頁面最後更新 2026-09-01；查閱 2026-09-12）
- [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)（Android Developers，頁面最後更新 2026-09-01；查閱 2026-09-12）
- [Manifest.permission — FOREGROUND_SERVICE_LOCATION](https://developer.android.com/reference/android/Manifest.permission#FOREGROUND_SERVICE_LOCATION)（Android API reference，查閱 2026-09-12）
- [Request location access at runtime](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)（Android Developers，頁面最後更新 2026-09-08；查閱 2026-09-12）

### 3.2 Android 15 的 FGS 變更

Android 15（API 35）針對 target 35 以上的文件列出：

- `dataSync` 與新增的 `mediaProcessing` FGS 在背景有總計 6 小時／24 小時限制，超時會收到 `Service.onTimeout()`，未停止可能造成錯誤／ANR。
- 某些 FGS 類型不能由 `BOOT_COMPLETED` receiver 啟動；列出的類型包括 `dataSync`、`camera`、`mediaPlayback`、`phoneCall`、`mediaProjection`、`microphone`。
- `location` 類型在官方 FGS 類型表中是長時間定位用途，要求位置權限與位置服務；上述 Android 15 6 小時段落只列 `dataSync` 與 `mediaProcessing`。因此文件支持「使用 location 類型承載定位輸出」的模型，但不應把它解讀成任何裝置上都永不被停止。

來源：[Behavior changes: Apps targeting Android 15 or higher](https://developer.android.com/about/versions/15/behavior-changes-15)（Android Developers，查閱 2026-09-12）；[Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)（Android Developers，查閱 2026-09-12）。

### 3.3 Android 16 的 FGS／工作配額變更

Android 16（API 36）官方行為變更指出，從 foreground service 執行的背景工作現在仍須遵守自己的 runtime quota；範圍包括直接排程的 `JobScheduler` 與由 WorkManager／DownloadManager 建立的工作。這是工作排程配額變更，不是文件中對 `location` mock API 的全域停止時間。

因此，「逐點輸出」若依賴 JobScheduler／WorkManager，必須把 Android 16 quota 納入驗證；本文不能替未來實作選擇 Handler、Executor、coroutine 或其他 tick 機制作產品決策。

來源：[Behavior changes: all apps — Android 16](https://developer.android.com/about/versions/16/behavior-changes-all#jobscheduler-quota-optimizations)（Android Developers，查閱 2026-09-12）；[Changes to foreground services](https://developer.android.com/develop/background-work/services/fgs/changes)（Android Developers，查閱 2026-09-12）。

### 3.4 Wake lock

官方 PowerManager 文件把 wake lock 定義為讓裝置保持醒著的機制，並要求 manifest 的 `android.permission.WAKE_LOCK`。官方「選擇保持裝置醒著的 API」指南的判斷順序是：

- 先用最輕量的機制；不要因為有 foreground service 就自動取得 wake lock。
- 如果螢幕關閉時，裝置 suspend 會使使用者可見的行程失去正確性，才考慮 wake lock。
- 取得後應在不需要時立即 `release()`；文件警告長時間持有會快速消耗電量。

來源：

- [PowerManager.WakeLock](https://developer.android.com/reference/android/os/PowerManager.WakeLock)（Android API reference，查閱 2026-09-12）
- [Manifest.permission — WAKE_LOCK](https://developer.android.com/reference/android/Manifest.permission#WAKE_LOCK)（Android API reference，查閱 2026-09-12）
- [Choose the right API to keep the device awake](https://developer.android.com/develop/background-work/background-tasks/awake)（Android Developers，頁面最後更新 2026-02-26；查閱 2026-09-12）

所查 Android 15／16 行為變更文件沒有另列 location wake lock 的固定時限。這只表示目前查到的公開文件沒有該條文；不代表 OEM 省電、Doze、電池最佳化、強制停止或裝置記憶體壓力下會保證連續輸出。

## 4. 與 Pokémon GO／Pikmin Bloom 的官方相容性邊界

### 4.1 系統 mock 與遊戲相容性不是同一個驗收

Android 官方文件把 mock location 定位在「測試 App 在其他地點的行為」，並且 Android API 提供 `Location.isMock()` 讓接收端辨識。這是系統能力與接收端選擇之間的契約；它沒有給第三方工具一個讓接收端必須接受的位置通道。

因此，跨 App 驗證只能回答「獨立接收 App 是否收到帶有座標、時間、速度與 mock 標記的資料」，不能回答某個遊戲版本是否接受、是否計算移動、是否允許帳號繼續遊玩。後者必須在明確授權與可接受風險的測試環境中另行取得版本／裝置證據。

### 4.2 Pokémon GO

Niantic 官方 [GPS Troubleshooting Guide](https://niantic.helpshift.com/hc/en/6-pokemon-go/faq/2520-gps-troubleshooting-guide/?return_to=%2Fhc%2Fen-us%2Fsections%2F204865728-Shop) 針對「failed to detect location」指出，裝置可能有一個 **mock locations** 設定阻止遊玩，並引導使用者到 Developer options 關閉 **Select mock location app**。這是官方明確的相容性警告，不是工具可以修正的 API 錯誤。

Niantic 官方 [Violating the Terms of Service](https://niantic.helpshift.com/hc/en/6-pokemon-go/faq/525-violating-the-terms-of-service/?l=en) 將 falsifying location、使用 emulator、modified or unofficial software，以及未授權的 third-party software／add-ons 列為可採取處分的行為。文件沒有承諾任何 mock location app 可與 Pokémon GO 相容；本專案不應加入繞過 mock 標記、root、修改遊戲或規避偵測的實作。

### 4.3 Pikmin Bloom

Pikmin Bloom 官方 [Special Spot](https://niantic.helpshift.com/hc/en/23-pikmin-bloom/faq/3623-special-spot/?l=en&p=web&s=trust-safety) 說明，若精確位置無法驗證，原因可能包括使用改變／偽造位置的工具或技術（spoofing）；該頁明確說明這違反 Niantic Terms of Service 與 Player Guideline。

其 [Required permissions to play Pikmin Bloom](https://niantic.helpshift.com/hc/en/23-pikmin-bloom/faq/4189-required-permissions-to-play-pikmin-bloom/?l=en) 說明遊戲需要裝置位置，且背景位置可能影響功能；這只能證明遊戲依賴位置，不能證明它會接受 mock。官方 [Three-Strike Discipline Policy](https://niantic.helpshift.com/hc/en/23-pikmin-bloom/faq/2868-three-strike-discipline-policy/?contact=1&openExternalBrowser=1&p=web&s=trust-safety) 也把 GPS location spoofing 與未授權 third-party software／add-ons 列為可能導致警告、停權或終止帳號的作弊行為。

## 5. 步行路線來源：Google 與 OSM／OSRM

### 5.1 Google Routes API `WALK`

Google 官方 Routes API 文件確認 `RouteTravelMode.WALK` 是步行模式。官方同時標示 `WALK`、`BICYCLE`、`TWO_WHEELER` 仍是 beta，可能缺少清楚的人行道、步行路徑或自行車道；顯示這類路線的 App 必須對使用者顯示警告。

`Compute Routes` 可回傳 `distanceMeters`、`duration` 與 encoded polyline。官方範例使用 `X-Goog-Api-Key` 和 `X-Goog-FieldMask`，Routes API 的 billing 文件要求每個專案啟用 billing 並使用 API key 或 OAuth token；因此它不是「安裝 App 後零 secret／零帳務」的來源。

Google Maps Platform 的 Routes API 政策與目前 Service Specific Terms 還有三個會直接影響地圖與儲存的限制：

- Routes API 結果顯示在地圖上時必須顯示在 Google Map；非 Google Map 顯示時必須遵守 Google Maps attribution，包括 Google Maps logo／文字要求。
- 目前條款第 19.2 寫明不得把 Google Maps Content from Routes API 與 non-Google map 合用。換句話說，不能假設「用 Google WALK 算 geometry，再畫在 OSM／MapLibre map」自然就符合條款。
- 目前條款第 19.3 允許 Routes API 經緯度暫存最多 30 個連續日；政策頁也要求應有公開 Terms of Use 與 Privacy Policy。路線請求中的起點、終點與途經點會送到 Google API，這是外部服務的資料流，必須在實作的隱私告知中說清楚。

來源：

- [RouteTravelMode — WALK](https://developers.google.com/maps/documentation/routes/reference/rest/v2/RouteTravelMode)（Google for Developers，頁面最後更新 2026-09-04；查閱 2026-09-12）
- [Available vehicle types for routes](https://developers.google.com/maps/documentation/routes/vehicles)（Google for Developers，頁面最後更新 2026-09-10；查閱 2026-09-12）
- [Get a route — Compute Routes](https://developers.google.com/maps/documentation/routes/compute_route_directions)（Google for Developers，頁面最後更新 2026-09-10；查閱 2026-09-12）
- [Routes API Usage and Billing](https://developers.google.com/maps/documentation/routes/usage-and-billing)（Google for Developers，查閱 2026-09-12）
- [Policies and attributions for Routes API](https://developers.google.com/maps/documentation/routes/policies)（Google for Developers，查閱 2026-09-12）
- [Google Maps Platform Service Specific Terms — §19 Routes API](https://cloud.google.com/maps-platform/terms/maps-service-terms#19_routes_api)（目前版本，頁面列出上一版最後修改 2026-06-10；查閱 2026-09-12）

所查官方文件只描述 API／SDK 的正式介面，沒有提供從 Google Maps 消費者 App 或網頁抓取「Walk」路線 geometry 的公開、無密鑰接口。這是根據文件範圍作的限制判讀，不是對 Google Maps 產品內部行為的 runtime 結論。

### 5.2 公開 OSM／OSRM foot router

`routing.openstreetmap.de` 自己的 [About 頁](https://routing.openstreetmap.de/about.html) 說明其服務使用 OSRM backend，目前頁面所列版本為 v5.27.1，提供 worldwide 的 car、bike、foot profile，路由資料約每兩天更新一次；該頁也說 foot profile 與 OSRM source 內建 profile 有差異。這是服務自述的部署資料，不是可用率承諾。

同一頁的 Privacy 段落明確寫出：路線 request 會送到該 server，並保存於 server log，隱私細節由 [FOSSGIS 隱私說明](https://www.fossgis.de/datenschutzerkl%C3%A4rung)處理。Usage policy 段落則要求顯示 attribution 與 [`fix the map`](https://www.openstreetmap.org/fixthemap) 連結、使用有效 User-Agent／適用時的正確 Referer、每秒最多一個 request，並禁止 scraping／heavy usage；該頁連到 [FOSSGIS routing server 使用政策](https://www.fossgis.de/arbeitsgruppen/osm-server/nutzungsbedingungen/)。這些是公開服務的全域使用政策邊界；它們不等於對本 App 的 SLA、可用率或錯誤恢復保證。

Project OSRM 的官方文件描述：

- HTTP route endpoint 形式為 `/route/v1/{profile}/{coordinates}`，profile 可以是 `foot`（實際可用 profile 取決於該 server 的 preprocessing）。
- `steps=true` 可要求 turn-by-turn steps；`geometries=geojson` 或 polyline 可取得路線 geometry；回應有 route distance、duration、geometry 與 legs。
- profile 在 preprocessing 時使用，不是在 query 時切換。自架時需用 OSM PBF 與 foot profile 進行 extract／partition／customize，再啟動 `osrm-routed`。
- Project OSRM 的官方 frontend 設定範例把 `https://routing.openstreetmap.de/routed-foot` 作為 foot backend URL，這能支持該 URL 形態的文件依據；本文件本身沒有向端點發請求，故不把端點可用性寫成 Android/runtime 證據。主線另於 2026-09-12 保存了一次 endpoint smoke test（`code=Ok`、約 581.7 m、42 個 GeoJSON points）至 `.runtime/evidence/foot-route.json`；那只證明該次請求的 HTTP／路由回應，不證明 Android App、其他地區、持續可用性或遊戲相容性。

對初版的「無 API key」語意，公開 foot router 有兩個需要同時說明的面向：

1. 使用者座標會送到第三方共用路由服務；`about.html` 明確說 request 會保存於 server log，並連到 FOSSGIS 隱私說明。其公開使用政策是每秒最多一請求、有效 User-Agent／Referer、attribution／fix-the-map 與禁止 scraping／heavy usage；這是服務使用上限與條件，不是本 App 的 SLA。應把資料外傳、log 保存細節、政策執行與長期可用性分開評估。
2. OSRM 官方文件提供自架流程，這可避免把座標送到公共端點，也不需第三方 API secret，但要自行下載／更新 OSM extract、維護伺服器與處理 ODbL attribution。它不是手機端「零運維」方案。

來源：

- [About routing.openstreetmap.de](https://routing.openstreetmap.de/about.html)（服務方公開頁，查閱 2026-09-12）
- [Project OSRM HTTP API documentation](https://github.com/Project-OSRM/osrm-backend/blob/master/docs/http.md)（Project OSRM 官方 repository，查閱 2026-09-12）
- [Project OSRM README／self-host quick start](https://github.com/Project-OSRM/osrm-backend#using-docker)（Project OSRM 官方 repository，查閱 2026-09-12）
- [Project OSRM profiles](https://project-osrm.org/docs/v26.4.0/profiles)（Project OSRM 官方文件，查閱 2026-09-12）
- [Project OSRM frontend — routed-foot configuration](https://github.com/Project-OSRM/osrm-frontend#using-docker)（Project OSRM 官方 repository，查閱 2026-09-12）

### 5.3 不使用外部路由服務的路線輸入

從系統能力與路由服務限制可分出一條不需 secret 的輸入方式：使用者提供既有 route geometry（例如 GPX、GeoJSON、encoded polyline 或手動座標序列），App 只做格式驗證、距離／時間計算與模擬輸出。這能讓模擬行程在沒有 routing network、API key 或外部服務時仍有輸入，但它不會驗證 geometry 是否沿可步行路網，也不會自動重算 Google Maps 的 walk route。

以下表格是服務與資料流的事實比較，不是產品選擇：

| 來源 | 是否需 API secret | 是否自動產生步行路網 | 路線資料是否離開裝置 | 主要限制 |
| --- | --- | --- | --- | --- |
| 使用者既有 GPX／GeoJSON／polyline | 否 | 否，輸入本身要有 geometry | 可不離開裝置 | 路線品質與合法步行性由輸入負責 |
| `routing.openstreetmap.de/routed-foot` | 所查文件未要求 key | 是，依 server 的 OSRM foot profile | 是，request 會記錄於 server log | 依公開政策每秒最多一請求、需 attribution／有效 User-Agent、禁止 scraping／heavy usage；無本 App SLA |
| 自架 OSRM foot | 不需第三方 API key | 是 | 可控制在自家服務 | 需 OSM extract、部署、更新與維運 |
| Google Routes API `WALK` | 需 API key 或 OAuth，且 billing | 是 | 是，送至 Google API | beta 警告、Google Map／attribution／non-Google map 與快取條款 |

## 6. 地圖顯示、MapLibre 與 OSM 授權

### 6.1 MapLibre 是 renderer，不是地圖資料授權

官方 [MapLibre Native repository](https://github.com/maplibre/maplibre-native) 說明其為可用於 Android 的開源地圖 renderer，核心 repository 採 BSD 2-Clause；[MapLibre Android API documentation](https://maplibre.org/maplibre-native/android/api/) 也列出 Android attribution API。這只說明 SDK 的程式碼授權與顯示能力，沒有自動授權任何 tile、style、路由結果或 POI 資料。

如果 map style／tile 來自其他供應商，必須各自查看該供應商的 attribution、快取、離線與商用條款。使用 MapLibre 不會把 Google Routes Content 變成可以與 OSM map 合用的資料。

### 6.2 直接使用 `tile.openstreetmap.org`

OSMF 的 [Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/) 和 [Copyright and License](https://www.openstreetmap.org/copyright) 明確區分「OSM data 是開放資料」與「OSMF tile server 不是無限量公共 CDN」：

- 正確 raster tile URL 是 `https://tile.openstreetmap.org/{z}/{x}/{y}.png`。
- 地圖上要清楚顯示 `© OpenStreetMap contributors` 等 attribution，並能連到版權／ODbL 說明。
- 原生 App 要用能識別自身的 User-Agent；不能冒充其他 App，也不應只送 HTTP library 的 generic User-Agent。
- 應遵守 HTTP cache header，若客戶端無法讀取，政策要求至少快取七天；不得預先大量抓取、建立離線 tile archive 或提供背景 prefetch。
- 服務為 best effort，沒有 SLA 或可用性保證；若流量造成服務負擔，可能被封鎖。商用服務尤其不能把 OSMF tile server 當成永久供應承諾。
- OSM data 採 ODbL，公開使用需 attribution；若分發資料庫或衍生資料，還要另行檢查 share-alike 義務。

上述 tile policy 是圖磚政策，不是 `routing.openstreetmap.de` 的專屬 rate-limit 政策；兩者不能互相代替。若地圖需要離線或可預期容量，OSMF policy 指向自架或有明確允許 offline／prefetch 的 OSM-derived provider，而不是大量下載標準 tile。

## 7. 未驗證項目與應保留的限制文字

以下項目在本文件中刻意沒有下「已可用」結論：

- Android 15／16 真機與特定 OEM 的 `LocationManager`、FLP、前景服務、Doze、省電與強制停止行為。官方 API 契約不等於每台裝置的穩定性證據。
- `routing.openstreetmap.de/routed-foot` 在查閱時的長期 HTTP 可用率、政策執行／封鎖行為、地區覆蓋與結果品質。官方 About 頁已提供每秒一請求與禁止 heavy usage 的政策文字，但不能取代 runtime check、隱私說明或服務協議；主線的單次 smoke test 也不能推導長期 SLA。
- Google Routes API 的帳戶／區域／配額／價格細節與實際回應。官方文件支持介面與條款判讀，但本次沒有 API key、billing project 或請求。
- Pokémon GO／Pikmin Bloom 任一版本在任何 Android 15／16 裝置上的遊戲結果。官方支援頁已足以證明不應承諾相容；它們不是跨 App verifier 的替代品。
- MapLibre、OSM tile、OSRM route 與遊戲服務的整體可用性。每個供應商的 SDK、資料與服務條款需要獨立核對。

任何對外說明都應使用「使用 Android 官方 mock location 能力，接收端可辨識 mock；特定 App／遊戲是否接受需另行驗證」這類精確文字，不能寫成「可繞過遊戲偵測」或「保證 Pokémon GO／Pikmin Bloom 可玩」。
