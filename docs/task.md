# Tasks: Cellular Filtering & HTTPS/WebCodecs Local Communication

- [x] Android 10.x.x.x Cellular Filtering & Local Interface Extraction
  - [x] Implement robust network interface filtering in [NetworkMonitor.kt](file:///c:/project/castla/app/src/main/java/com/castla/mirror/network/NetworkMonitor.kt)
  - [x] Update Compose UI serverUrl logic in [MainActivity.kt](file:///c:/project/castla/app/src/main/java/com/castla/mirror/MainActivity.kt) to adapt to `webCodecsEnabled` settings
  - [x] Implement background NestJS IP registration HTTP request in [MainActivity.kt](file:///c:/project/castla/app/src/main/java/com/castla/mirror/MainActivity.kt)
- [x] NestJS Backend User-Isolated IP Mapping
  - [x] Create [castla.service.ts](file:///c:/project/tesla_manager/manager/src/tesla/castla.service.ts) with in-memory `Map<string, string>`
  - [x] Create [castla.controller.ts](file:///c:/project/tesla_manager/manager/src/tesla/castla.controller.ts) with POST/GET endpoints
  - [x] Import controllers and services into [tesla.module.ts](file:///c:/project/tesla_manager/manager/src/tesla/tesla.module.ts)
- [x] Nginx Reverse Proxy Config
  - [x] Update Nginx configuration guidelines in [car.conf](file:///c:/project/tesla_manager/nginx/car.conf) (Automatic Generic reverse proxies verified)
- [x] Svelte 5 Hybrid Playback Front-end
  - [x] Create Svelte 5 route at [castla/+page.svelte](file:///c:/project/tesla_manager/viewer/src/routes/castla/%2Bpage.svelte)
  - [x] Implement automatic HTTPS/WebCodecs and HTTP/MSE switcher and canvas/video rendering pipelines
- [x] Documentation & Verification
  - [x] Copy final walkthrough/docs to [c:\project\castla\docs/](file:///c:/project/castla/docs/) for local access
