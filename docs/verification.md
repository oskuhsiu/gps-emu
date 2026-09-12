# 初版驗證紀錄

日期：2026-09-12。版本：0.1.0 debug。下述為實際執行結果；SDK 文件、建置成功或本 App 的 marker 都不當成跨 App 成功。

版本庫保留本報告、APK 指紋與接收器數值摘要。原始裝置 log、截圖、系統 dumpsys、建置／lint／JUnit 輸出和路由回應留在本機 `docs/evidence/2026-09-12/`，由 `.gitignore` 排除；下文的原始檔名是本機證據索引，不表示這些檔案已公開。外部讀者可依「重現」步驟重新取得樣本。

## 環境與接縫

| 環境 | 實際系統 | 說明 |
| --- | --- | --- |
| Android 15 | API 35、Pixel Tablet、x86_64、Google Play 系統映像 | Wi-Fi-only tablet；專案獨立 AVD |
| Android 16 | API 36、sdk_gphone64_x86_64、Google Play 系統映像 | 專案獨立手機 AVD |

沒有連接實體手機。並行冷啟動時曾有系統／System UI 無回應，後改逐台取得下列結果；不把環境錯誤當成產品成功或實機證據。

主程式 `dev.routemock.app` 與接收器 `dev.routemock.probe` 是不同 package／UID。接收器使用公開 GPS、network、Google fused API，記錄實際收到的 JSON 樣本；不讀主 App 私有狀態判斷成功。測試用 ADB 為隔離 AVD 授予權限與 mock app op，操作走真實主畫面的座標、規劃、速度與控制。

## 結果

| 行為 | Android 15 | Android 16 |
| --- | --- | --- |
| 主 APK／接收 APK 安裝與啟動 | PASS | PASS |
| 地圖、途經點、foot 路由預覽 | PASS | PASS |
| 定點：三通路指定座標／零速／isMock | PASS | PASS |
| 沿路線行走，5 km/h | PASS | PASS |
| 變速 20 km/h，約 5.556 m/s | PASS | PASS |
| 暫停定點／繼續 | PASS | PASS |
| 終點零速定點 | 未另測 | PASS |
| 通知停止、test providers 移除 | PASS | PASS |
| 停止後新 GPS／fused 樣本 isMock=false | PASS | PASS |
| 未選 mock App 時顯示設定引導 | PASS | 未另測 |
| 最低速度 0.5 km/h 儲存、程序重建後顯示 | PASS | 未另測 |
| 已下載路線離線行走 | PASS | 未另測 |
| 無步行路線：錯誤明確、禁止開始 | PASS | 未另測 |
| 通知開啟重用單一 MainActivity | 未另測 | PASS |
| 強制停止 → 重開中斷提示 → 清理 | 未另測 | PASS |

Android 16 中斷復原後另取得 3 筆新 GPS 非 mock 樣本；該短觀察窗未取得新 fused 樣本。正常通知停止在兩個系統都有新 GPS／fused 非 mock 樣本。停止後使用 emulator console 提供新的測試 GPS fix，確認非 mock 接收；這不代表實體手機能立即取得真實定位。

### 數值證據

[機器檢查结果](evidence/2026-09-12/receiver-assertions.json)記錄每通路樣本數、時間、速度與路徑誤差。排除最初 2 秒可能的快取／註冊過渡，再檢查 timestamp 遞增、isMock=true、速度、定點無漂移與路徑 10 m 容差。距離計算使用獨立 Python 幾何程式，未呼叫產品引擎。

- API 35 定點：三通路各 15 筆穩定樣本，跨 14.271 秒，位移 0。
- API 35 的 5 km/h：GPS 穩定觀測 13.258 秒，移動 18.413 m；speed 約 1.388889 m/s。
- API 35 離線 20 km/h：三通路各約 15 筆穩定樣本，GPS 跨 14.249 秒；本機 `api35-offline-connectivity.txt` 明示 `Active default network: none`。
- API 36 的 5／20 km/h：穩定觀測約 8.149／8.164 秒，完整接收窗超過 10 秒；speed 約 1.388889／5.555555 m/s。
- API 36 到達：三通路各 18 筆穩定終點樣本，跨約 17.297 秒，位移與速度皆 0。

誤差是相對指定數值／規劃 geometry 的誤差，**不是物理 GPS 精度**。Stop 不以 lastLocation 舊快取判定仍在輸出，而檢查目前 providers 與新的樣本。

### 建置與畫面

- 主 App／probe debug APK 建置成功；[路徑、大小與 SHA-256](evidence/2026-09-12/artifacts.json)。
- 12 項 JUnit 測試，0 failures／0 errors（本機 `core-tests.xml`）。涵蓋變速、暫停／繼續、延遲跨段、到達、重複點、換日線、極區、無效輸入與向後時間。
- Android lint：主 App 0 errors／1 warning；probe 0 errors／4 warnings。警告是固定的 GMS 版本有新版、測試接收器備份設定與兩處動態字串。
- Leaflet 1.9.4 JS／CSS 已與官方 SRI SHA-256 比對。
- 公開 foot router 台北請求 code `Ok`、581.7 m、42 點；原始回應保存在本機 `foot-route.json`。
- 實際 render：本機 `api35-route-preview.png`、`api36-route-preview.png`、`api35-probe-walking.png`、`api35-no-route.png` 分別記錄 Android 15／16 預覽、接收器與無可用路線。控制、狀態、system insets 與 attribution 可見，設定區可捲動。
- 修正相同文字重複更新造成的無障礙事件；automation 直接讀取節點，避免把舊 uiautomator XML 當成新結果。
- 修正通知堆疊主畫面而覆寫草稿；通知重用現有 Activity，onResume 重載保存內容。

## 重現

建置指令見 README。兩個 APK 安裝在同一測試裝置；先授權位置、通知並指定 mock App。

1. 在主畫面輸入 `25.0330,121.5645`、`25.0350,121.5680`，先定點，再規劃並開始步行。
2. 開啟 Route Probe，自動註冊接收；分別記錄定點、5 km/h、暫停、20 km/h、終點與停止。
3. 以 `adb logcat -d -v raw -s RouteProbe:I '*:S'` 取得 JSON；搭配 `tools/verify_probe.py` 檢查數值。
4. 通知停止，再檢查目前 `dumpsys location` provider 區段及新的非 mock 樣本。

`tools/ui_device.py` 使用 probe 的 debug-only UiDriver，透過精確文字／content-description 操作。每次 instrumentation 會重啟 probe 程序，因此收樣時另行開啟 ProbeActivity，期間不執行 driver。產品 APK 沒有測試後門或 exported 定位 service。

## 首次公開前的檔案完整性檢查

2026-09-12 整理 `.gitignore` 後，只匯出 Git 暫存區的檔案至乾淨目錄，以既有 JDK／SDK 與依賴快取執行 `:core:test :app:assembleRelease :app:lintRelease :probe:assembleDebug`：87 個 tasks 全部執行成功，12 個核心測試無失敗，release lint 為 0 errors／0 warnings。這次檢查確認版本庫包含建置所需的原始碼與 Gradle wrapper；沒有重新執行裝置功能測試。

另驗證 29 個應忽略與 18 個應保留的路徑案例，以及公開 Markdown 連結。Git 暫存區的 Leaflet 三檔、Gradle wrapper JAR 與兩個啟動腳本，均保留安全檢查時的原始 SHA-256；`.gitattributes` 避免對這些檔案進行換行轉換。

## 未驗證與限制

- Pokémon GO／Pikmin Bloom **未實測、不保證可玩**；mock 標記保持可辨識，原始遊戲使用情境尚未被證明成立。
- 未測實體手機／其他 OEM、長時間 screen-off／Doze、省電豁免、超長行程與 GMS 缺席／異常裝置矩陣。
- 權限撤回、GMS Task 長時間不完成、所有外部服務故障型態未做裝置故障注入；有錯誤／清理實作，不宣稱全部已測。
- UI 檢查限上述 portrait 視窗；未完成 TalkBack、全部字型縮放與橫向矩陣。
- 公共路由／圖磚沒有 SLA；本次結果不外推到未來 Android 版本。
- 全面 CVE／商用發行授權稽核與 release 上架未完成。使用者後續提供 GitHub 倉庫；規格已發布為 [issue #1](https://github.com/oskuhsiu/gps-emu/issues/1)，標籤為 ready-for-agent。
