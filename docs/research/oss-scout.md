# Open-Source Scouting Report: Android 15+ 系統級 Mock GPS 與路線播放

> Decision Verdict: Option D
> Estimated MVP Delivery: 7–10 個工作天（單一 Android 開發者；包含一台 Android 15 實機驗證）
> Primary Recommendation: Independent custom build；僅選用相容的 MIT/Apache-2.0 通用依賴，從 Android 公開 mock-location API 自行實作

本報告以 2026-09-12（Asia/Taipei）可取得的公開資料為準。README 的功能描述、GitHub 的建置／發行證據與實機行為是不同等級的證據；除有明確 CI 或發行 APK 外，本報告不把 README 的「可用」描述視為已完成實機驗證。

## 1. Requirement Scope and Boundaries

- Core Business Loop: 使用者選擇單點或步行路線與移動速度 → 儲存一個 mock session → 前景服務持續寫入 Android mock provider，使其他位置 App 收到移動中的座標。
- Target Delivery Type: Mobile（Android 15 以上；MVP minSdk 35、target/compile SDK 36、Java 17）。
- Licensing Constraint: Commercial Closed-Source；可以參考公開 API 與行為規格，直接整合僅限授權相容的 permissive 依賴。

邊界與可行性結論如下：

- Android 的公開路徑是 Developer options → Select mock location app，再由 `LocationManager` test provider（以及有 Google Play services 時的 Fused Location Provider）送出 mock fix。官方 API 明確標示這些位置為 mock，消費端可用 `Location.isMock()` 辨識；因此公開 API 不能保證 Pokémon GO、Pikmin Bloom 或其他具有反作弊／Play Integrity 檢查的遊戲接受它。
- 步行 routing 是 MVP 必要能力；候選的 OSRM 路由多為 driving，或只讓使用者手動放置 waypoint。MVP 應接上文件化、可商用審核的 OSM foot router，並把路由供應商包在可替換介面中；Google Maps 只是使用者舉例，不作為技術依賴或抓取來源。
- 「影響其他 App」與「繞過遊戲的 mock 偵測」是兩個不同目標。前者可用公開 Android mock-provider 流程，後者不在本案承諾範圍，也不應把自然抖動或速度模型描述成可繞過偵測。
- Android 14/15+ 對長時間背景執行的 foreground service 有 service type、權限與啟動時機要求；路線播放必須讓使用者在 App 可見時開始，顯示持續通知，並針對 Android 15 實機驗證電池最佳化、螢幕關閉及服務重啟行為。

平台依據：

- [Android `LocationManager` API](https://developer.android.com/reference/android/location/LocationManager)：`addTestProvider`、`setTestProviderLocation` 與 `removeTestProvider` 需要 mock location app op，且 test provider 的位置會送給系統位置客戶端。
- [Android `Location.isMock()`](https://developer.android.com/reference/android/location/Location.html)：Android 12/API 31 起可辨識 mock location；舊的 `isFromMockProvider()` 已 deprecated。
- [Android Developer options](https://developer.android.com/studio/debug/dev-options)：官方說明 Select mock location app 用於測試不同位置。
- [Google Play services `FusedLocationProviderClient`](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)：`setMockMode`/`setMockLocation` 影響同一裝置上其他 FLP client，但要求 App 被選為 mock location app；位置同樣會被標示為 mock。
- [Android foreground-service location requirements](https://developer.android.com/develop/background-work/services/fgs/service-types)：Android 14+ 需要宣告 location service type 與相應權限；背景啟動及 `ACCESS_BACKGROUND_LOCATION` 的限制必須在實機測試。

## 2. Shortlisted Candidates

Stars、default branch、最新提交與授權均以 GitHub REST metadata 或倉庫中的 LICENSE 檔在查詢日重新核對；下列日期為 commit author date（UTC）。

| Repository | URL | Stars | Last Commit | Tech Stack | License | Risk Level | Rating |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `vincenzobpt/gps-mock-location` | [GitHub](https://github.com/vincenzobpt/gps-mock-location) | 1 | 2026-08-18 (`1463e7a`) | Kotlin 2.1、Compose、osmdroid、OSRM/Retrofit、foreground service | LICENSE 檔為 MIT；GitHub API detector 回傳 `NOASSERTION` | Warning | 3/5 |
| `Gegirhasut/route-spoofer` | [GitHub](https://github.com/Gegirhasut/route-spoofer) | 2 | 2026-07-29 (`a92191d`) | Capacitor 7、Kotlin native service、Play services location、JDK 21；target API 36 | MIT | Warning | 3/5 |
| `Sriharan-S/gps-mock` | [GitHub](https://github.com/Sriharan-S/gps-mock) | 13 | 2026-08-30 (`57eec5b`) | Flutter、MapLibre/OSM、Photon、OSRM、Android foreground service | MIT | Warning | 3/5 |
| `narumiruna/kestrel` | [GitHub](https://github.com/narumiruna/kestrel) | 13 | 2026-09-12 (`c6c04b3`) | Kotlin、Compose、MapLibre；Android app + 可選 cloud/web workspace；target API 36 | AGPL-3.0 | Fail（商用閉源不可直接採用） | 3/5 |

候選排除記錄：

- `liujiayu5566/MockGps` 的 GitHub metadata 顯示 564 stars、146 forks、2026-05-11 最新提交，README 具備模擬導航與檔案路線描述；但倉庫沒有 LICENSE 檔，README 又明確禁止修改後分發及商業牟利，因此不進入可商用 shortlist。
- `YYLMZXC/Portal` 是 Apache-2.0 且聲稱支援 Android 7–15，但依賴 LSPosed/Xposed、需要模組啟用及重開機，主要是 hook／搖桿定位，README 沒有與本案相符的路線播放證據；它不適合作為無 root 的核心基線。
- `orestislef/mock-path` 是 MIT 且有手繪路線與速度播放，但查詢日只有 0 stars、5 commits、無 release asset／Action run；可作為產品想法參考，不能以此證明維護或可用性。

## 3. Five-Pillar Due Diligence

### `vincenzobpt/gps-mock-location`

- Maintenance & Governance: GitHub `main` 有 17 commits，2026-08-18 有最新提交，v1.3.0 在 2026-08-18 發行；API contributors 顯示單一可辨識帳號，0 forks、0 issues，bus factor 很低。沒有公開 GitHub Actions run，因此「可建置」主要由 repository 的 Gradle wrapper、README 指令與 release APK 支持。
- Community Health: 1 star、0 forks、0 issues，沒有足夠的獨立使用者回饋或長期維護證據。
- Setup Friction: README 給出 JDK 17/21、Android SDK API 35、`./gradlew assembleDebug`／`build.sh` 與 Developer options 三步流程；v1.3.0 release 有 `MockLocation-v1.3.0.apk`，可降低初次建置門檻。
- Modularity & Reusability: README 將路由、simulation engine、mock-location backend、foreground service 與 UI 分層，並描述可替換的 backend interface；功能覆蓋單點、waypoint、OSRM 路由、速度、停留、loop、pause/seek 與跨 App provider 注入，接近本案核心迴路。但它的 OSRM profile 是 driving，步行 routing 仍需由自有介面接入。
- Supply Chain & Licensing: 倉庫 LICENSE 實際內容是 MIT，但 GitHub API `license.spdx_id` 為 `NOASSERTION`，需要在採用前人工保留 LICENSE 與依賴清單並完成 license/CVE scan。README 列出 osmdroid、Retrofit、OSRM public API 等外部依賴；本輪未取得完整 CVE 掃描結果，不能宣稱零漏洞。
- Summary: 行為與 Android 15（target SDK 35）最貼近，可作為需求對照與測試案例來源；社群太小、授權 detector 不一致、沒有獨立實機證據，故不直接採用。

### `Gegirhasut/route-spoofer`

- Maintenance & Governance: GitHub `main` 有 75 commits，最新提交 2026-07-29；GitHub Actions 的 `Android Debug APK` run 在同日成功，並有 2026-06-25 release APK。metadata 顯示 0 issues、0 forks、contributors API 沒有可辨識 contributors，維護集中度高。
- Community Health: 2 stars、0 forks，README 聲稱經獨立 QA provider beta test，但公開資料沒有可重播的裝置 log；因此將 QA 描述記為 maintainer claim，不等同於本輪實機驗證。
- Setup Friction: README 支援 GitHub Actions artifact 或 Android Studio/JDK 21；需 sideload、選擇 mock location app、授予 location/notification，再用 GPS/GO 控制。target API 36、minSdk 23 由 build metadata 核對，Android 15 runtime 本身仍需實機回歸。
- Modularity & Reusability: README 描述路線編輯、每段速度、全域速度、停留、loop/ping-pong、JSON import/export、foreground service，以及 GPS/NETWORK/Fused provider；`RouteEngine` 與 injector 的責任分離對本案有參考價值。它沒有內建步行 routing provider，MVP 仍需接入 OSM foot router。
- Supply Chain & Licensing: MIT LICENSE；依賴含 Capacitor、Kotlin、Google Play services location。公開 release/CI 證明建置產物存在，但本輪未做完整 dependency CVE scan，且 Play services/網路位置可能受裝置設定影響。
- Summary: 需求覆蓋度高且有可核對的 CI/APK 證據；小社群、Android 15 實機與遊戲接受度未獨立驗證，適合行為參考或獨立重寫。

### `Sriharan-S/gps-mock`

- Maintenance & Governance: `main` 有 48 commits，2026-08-30 最新提交；同日 `Android Release` workflow 有成功 run，但在成功前也有多次失敗 run；2026-08-30 release 含 `GPS.Mock-Universal.apk`。contributors 主要是作者與 automation，1 issue、4 forks。
- Community Health: 13 stars、4 forks、1 issue，較前兩個候選有多一些採用訊號，但仍沒有可驗證的 Android 15 實機矩陣或遊戲回報。
- Setup Friction: README 的 Flutter SDK、Android Studio/VS Code、`flutter pub get`、`flutter run`／`flutter build apk` 流程清楚，OSM/Photon/OSRM 不需 API key；Flutter toolchain 與 native mock provider bridge 增加排錯邊界，target SDK 沿用 Flutter 變數而非在 README 或 build metadata 明確固定。
- Modularity & Reusability: README 覆蓋單點 mock、路線、停留、duration/arrive-by 速度換算、foreground service、Quick Settings tile、widget、history 與離線地圖；路線仍以 OSRM 實際道路（README 範例為 driving）為核心，步行 routing 需另接 OSM foot router。對 MVP 而言功能超出 1-1-1-1 需要，整合成本高於只取行為規格。
- Supply Chain & Licensing: MIT；依賴 Flutter、MapLibre、OSRM、Photon、geocoding 與大型離線地圖能力，應在自建版本中分開審核服務條款、OSM attribution、license 與 CVE。本輪沒有完整 dependency CVE 掃描或端到端裝置證據。
- Summary: MIT、release APK 與成功 CI 讓它成為有用的產品參考；Flutter/native 邊界、target SDK 未明確固定、前後幾次 CI 失敗與無獨立 runtime evidence，使其不適合作為直接採用基線。

### `narumiruna/kestrel`

- Maintenance & Governance: `main` 有 771 commits，2026-09-12 最新提交；同日 main CI、Deploy 與多個 CI run 成功，v0.6.2（2026-09-04）含 release APK。公開 metadata 顯示 4 issues、3 pull requests、0 forks，主要人類 contributor 為單一作者，另有 automation。
- Community Health: 13 stars、0 forks；提交頻率與 CI 很強，但 stars/forks 與獨立 contributors 仍少，不能把活躍提交等同於廣泛採用。
- Setup Friction: 核心 App 可直接安裝 APK，要求 Android 10/API 29 以上，Developer options 選 app、授予 permission 即可；可選 cloud/web workspace 需要 Node、Docker、PostgreSQL，對 MVP 是額外部署負擔。README 明確要求關閉 Google Location Accuracy 以避免 Wi-Fi/cell override。
- Modularity & Reusability: README 覆蓋單點、手動 route、速度、Once/Loop/Ping-pong、random walk、foreground service、通知、收藏與可選 remote control，並明確使用官方 mock-location API；它對需求行為覆蓋最高，但 cloud、多 workspace 與 schema 超出 MVP。
- Supply Chain & Licensing: LICENSE 與 GitHub detector 均為 AGPL-3.0。對商用閉源版本不可直接 fork、複製或 link 成為衍生交付；本報告只提取公開行為／API 規格，沒有複製其 implementation。其 cloud 依賴與 Play services 整合亦需獨立 license/CVE 審核。
- Summary: 是最好的行為與 Android 15+ 操作參考之一，也是最明確的授權邊界；只採納公開需求與官方 Android API，不採用其程式碼或 AGPL 依賴。

跨候選共同的 runtime 限制：Route Spoofer README 報告 Google Maps/maps.me 通過，但也報告某些 App 會以網路定位覆蓋 mock；Kestrel README 報告 Play Integrity/SafetyNet 保護的 App 可偵測 mock；Android 官方 API 亦確認 `Location.isMock()`。因此「能注入到其他 App」可測試，「能讓特定遊戲接受且不回到真實位置」不能由任何候選的公開聲明保證。

## 4. Architectural Decision Verdict

- Recommended Path: Option D
- Key Justification: 沒有候選同時滿足「商用閉源授權、Android 15+ 實機可重現、系統級跨 App 注入、步行 routing、路線與速度、可維護社群」；Kestrel 的 AGPL-3.0 排除直接採用，其餘 MIT 候選的社群與 runtime 證據不足，且各自的路由 profile、target 與測試矩陣不同。以 Android 官方 mock-location API 獨立自建最小核心，可以把 provider injector、步行 route engine、foreground lifecycle 與 UI 分開驗證，並保留未來路由服務的替換點。

建議只重用以下「授權相容且與業務無關」的依賴類別，仍需在實作前鎖版本與掃描：

- 地圖／繪製：選一個 MIT/Apache-2.0 的 OSM map renderer；不要把候選 App 的 UI 或 route engine 複製進來。
- 路由／資料：MVP 使用在整合前完成驗證的 OSM foot router 產生步行路線；路由請求與 polyline 解碼放在可替換介面，並審核服務條款、attribution 與速率限制，不抓取 Google Maps UI。
- 系統注入：以 Android SDK `LocationManager` test provider 為基線；有 GMS 的裝置再以 `FusedLocationProviderClient` 作可選補強，並讓每個 mock fix 保持遞增 timestamp、速度、bearing 與 accuracy。

## 5. Minimalist MVP Plan (1-1-1-1 Rule)

- Core User Story: As a default mock-session user, I can choose a point or a walking route, set a speed, and start/stop playback, so that a foreground service reports the simulated position to other location-based apps while the device stays on the desk.
- Starter Architecture: 單一 Android App；Java 17 + Android platform SDK，一個單畫面包含起點／終點、步行路線預覽、速度與播放控制；一個 `RoutePlan` persisted record；一個 `RouteEngine` 產生時間序列座標；一個 `MockLocationService` 在前景通知下將每筆 fix 寫入 platform provider；先把 foot router 與 provider 寫入包在可替換介面後，再做 JVM route-engine tests 與 Android 15 device smoke test。
- Minimal Schema Draft:
  ```
  Entity: RoutePlan
  - id: primary key
  - points: ordered latitude/longitude list
  - speedMps: positive number
  ```
- Milestones:
  - Phase 1: 建立 Android 15 baseline、單點 mock、Developer options 引導、前景通知與停止清理；驗證 Google Maps/自製 location probe 能收到 `isMock` fix。
  - Phase 2: 接上選定的 OSM foot router，加入步行路線預覽、線性插值、固定速度、pause/resume/stop 與本機持久化；在 Android 15 實機鎖屏、切換其他 App、服務停止／重開時測試。
- Scope Blacklist:
  - [ ] No authentication or multi-user accounts
  - [ ] No microservice decomposition、雲端同步或 remote control
  - [ ] No game-specific anti-detection、root/LSPosed/Xposed hook 或改寫 Play Integrity
  - [ ] No route loop、歷史紀錄、收藏、匯出格式或複雜離線地圖
  - [ ] No analytics dashboard、主題切換或 i18n

  MVP 之後再評估：loop/ping-pong、路線收藏與歷史、GPX/GeoJSON 匯入、Quick Settings、widget、雲端遙控與多裝置同步。

## 6. Next Actions

- [ ] Action 1: 以 Android 官方 `LocationManager` mock provider + foreground-service requirements 建立自有 Android 15 prototype，先用固定座標 probe 驗證跨 App 收到 mock fix；保留 `isMock` 可見性與遊戲拒絕作為明確產品限制。
- [ ] Action 2: 定義 `RoutePlan`／`RouteEngine` 行為測試與 Android 15 實機矩陣，完成 OSM foot router 的功能、license/CVE/attribution 與速率限制 review。
