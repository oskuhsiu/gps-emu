# Route Mock 初版設計

狀態：設計經自問自答收斂，初版已實作；執行證據與未驗證範圍見 verification.md。日期：2026-09-12。

## 使用者需求與完成證據

使用者要一個 Android 15 以上的自有 fake/mock GPS App：位置影響其他 App、沿規劃的步行路線移動、速度可設定。Pokémon GO／Pikmin 是使用情境，因此必須分別記錄系統輸出與遊戲是否接受，不能把兩者混為一談。

完成一圈：地圖選點 → 規劃步行路線 → 儲存最近路線／速度 → 開始 → 切到另一個 App 接收位置 → 暫停／繼續／停止。

產品初版以免 root、使用者在開發人員選項指定 mock App 的官方 Android 能力為基線。公開 API 保留 mock 標記；Pokémon GO 官方已指出 mock location 可能阻止遊玩。初版不承諾能在這兩款遊戲使用；這是尚未達成的使用情境，不是已通過的驗收。[Android API](https://developer.android.com/reference/android/location/LocationManager)、[Pokémon GO 官方說明](https://niantic.helpshift.com/hc/en/6-pokemon-go/faq/2520-gps-troubleshooting-guide/?hl=en&l=en)

## 最小範圍

- 一種使用者：操作自己手機的人，不需要帳號。
- 一個主畫面：地圖、選點／路線、速度、控制與必要設定。
- 一個核心資料實體：最近行程草稿（途經點、已規劃軌跡、速度）。活動狀態不在重開 App 後自動恢復。
- 一個完整流程：輸入、儲存、系統模擬輸出、由另一 App 驗證。

初版提供定點、2–8 個途經點的步行規劃、0.5–30 km/h 速度（預設 5）、開始／暫停／繼續／停止、距離與狀態。到達終點後定點。行進中改速度從當前進度生效；編輯路線需先停止。新的規劃失敗時不可把直線偷偷當步行路線。

「像 Google Maps 的 walk」解讀為沿可步行路網規劃；不要求 Google 相同演算法或相同路線。初版使用 OpenStreetMap 路網與公開 foot router，不要求 API key。Google WALK 是之後可評估的供應商整合，需要費用、key 與條款決策。

## 畫面與內容契約

使用 Android 原生單頁控制與內嵌互動地圖。上方顯示 Route Mock／狀態；中間地圖有縮放、途經點、軌跡與目前位置；下方清楚顯示選點數、路線長度、速度及開始／暫停／停止。使用中以狀態與按鈕共同表示，不只用顏色。

地圖點按加入途經點，另有經緯度輸入，讓無地圖圖磚時仍可定點。路線規劃需使用者按下按鈕；畫面說明會送出選取的座標到 routing.openstreetmap.de，圖磚請求則送到 OpenStreetMap。沒有背景蒐集真實位置。單一繁體中文介面，不新增語言／主題切換。

原生控制最小觸控區 48 dp；狀態文字可讀、可捲動；處理 Android 15 edge-to-edge 系統列與鍵盤遮擋。地圖永遠保留 OpenStreetMap attribution。網路錯誤呈現於操作區，清楚提供重試；地圖失敗不表示模擬行程已停止。

## 技術輪廓

```text
單頁地圖與控制 -> 最近草稿
       |           |
       +-> 行程引擎 -> 模擬輸出 -> Android GPS / network
                         |      -> Google fused（可用時）
                         +-> 前景通知與狀態

獨立接收 App <---------- 裝置定位 API
```

原生 Java 17、Android SDK min 35 / target 36、Gradle 與 Android Gradle Plugin 固定版本。這個小型專案以平台元件減少框架與依賴；行程引擎用純 Java 做確定性測試。地圖採本機打包的 Leaflet 與 OSM 圖磚；GMS 裝置額外使用官方 FusedLocationProviderClient。

行程引擎依單調時間與公尺距離沿軌跡內插，處理零長度段、重複點、跨換日線、延遲 tick、多段跨越和終點，避免 tick 數當時間。定位每秒目標一次，填入座標、accuracy、speed、bearing、wall time、elapsedRealtimeNanos；暫停／到達的 speed 為 0。

可見 Activity 啟動 location foreground service；通知保留停止入口。所有開始、tick、變速、暫停、停止及 GMS Task 依序執行，只有成功開啟輸出後才顯示進行中。沒有開機啟動／背景偷開／自動續走。首次設定需位置權限、系統定位開啟、開發人員指定本 App；通知授權拒絕不應崩潰。

正常停止釋放本行程啟用的 test providers、關閉 fused mock mode、釋放資源。異常終止可能留有系統或接收端狀態，記錄未完成清理標記，下次開啟提供清理；清理失敗仍顯示未確認。恢復真實位置需要接收端取得新 fix，不能承諾停止瞬間完成。切換其他前景 App 是必要驗收；Doze／廠商省電／強制停止的長時間穩定性另外列證據。

## 分期与後續想法

1. 證據與設計：開源查證、自我 grill、ADR、可執行 spec。
2. 初版完整迴圈：原生 App、步行路由、行程引擎、系統 mock、接收器。
3. 驗證：建置、lint、行程測試、Android 15／16 可用環境的跨 App 證據。不能測的項目明列待驗。

後續候選（不加入初版）：GPX 匯入、路線往返／循環、途經點停留、通知直接調速、常用地點、接收 App 相容性檢查。GPX 可改善路由供應商不可用的情況；停留和循環可讓重複路線更省操作。
