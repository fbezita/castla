# Implementation Plan: Cellular Filtering & HTTPS/WebCodecs Local Communication with User Isolation

This plan details the implementation of a high-performance, secure context (HTTPS/WebCodecs) and non-secure context (HTTP/MSE) hybrid mirroring system for Tesla Castla, leveraging Cloudflare NS wildcard delegation for dynamic local SSL routing.

---

## User Review Required

Please review the architectural details of the Cloudflare NS delegation.

> [!NOTE]
> **Cloudflare DNS Wildcard NS Delegation Configuration**
> To support dynamic SSL validation on local private IPs (`192.168.x.x`) without registering each IP manually, you must add a single NS record in your Cloudflare DNS control panel for the domain `fbezita.com`:
> - **Type**: `NS`
> - **Name**: `ip` (represents `*.ip.fbezita.com`)
> - **Nameserver**: `ns-aws.sslip.io` (and optionally `ns-gce.sslip.io` as a backup)
>
> This routes any query for `[A]-[B]-[C]-[D].ip.fbezita.com` to `sslip.io`, which dynamically resolves it to the local IP `A.B.C.D`. Since the subdomain ends in `fbezita.com`, your wildcard SSL certificate `*.ip.fbezita.com` or `*.fbezita.com` will validate perfectly on the Tesla Browser without any SSL warnings!

---

## Proposed Changes

### 1. Android Workspace (`c:\project\castla`)

We will adjust the network interface scanning logic to prioritize wireless/hotspot adapters and ignore carrier cellular IP ranges starting with `10.`. We will also add a guideline/logic to register the local IP to the NestJS signaling backend during service startup.

#### [MODIFY] [NetworkMonitor.kt](file:///c:/project/castla/app/src/main/java/com/castla/mirror/network/NetworkMonitor.kt)
- Update `getDeviceIp()`:
  - Skip cellular interfaces (`rmnet`, `ccmni`, `p2p`, `ppp`) to prevent carrier IP interference.
  - Skip any candidate IP starting with `10.` to strictly filter out cellular network private IPs.
  - Scan and prioritize tethering, hotspot, and Wi-Fi virtual interfaces (`ap`, `softap`, `swlan`, `wlan`) with private IPs in `192.168.x.x` and `192.0.0.4` ranges.

#### [MODIFY] [MainActivity.kt](file:///c:/project/castla/app/src/main/java/com/castla/mirror/MainActivity.kt)
- Add API registration method `registerPhoneIpWithSignalingServer(userId: String, localIp: String)`:
  - Trigger this registration asynchronously inside the coroutine scope when starting the mirroring service.
  - We will use an asynchronous HTTP client (such as OkHttp already bundled in the project or a standard `HttpURLConnection` thread) to send `POST https://car.fbezita.com/api/castla/register-ip` with body `{ "userId": userId, "ip": localIp }`.
  - For standard deployment, a unique user identifier (e.g. `Settings.Secure.ANDROID_ID` or a custom configured `userId` in Settings) will be passed.
- Update `updateServerUrl()`:
  - Modify UI案内 (display of `serverUrl` Compose state) based on `streamSettings.webCodecsEnabled`.
  - **If `webCodecsEnabled` is true**: Display **`https://car.fbezita.com/castla`** (or parameterized with `?userId=xxx` using a secure identifier) to guide users to the secure context.
  - **If `webCodecsEnabled` is false**: Fallback to display **`http://${localIp}:9090`** (pure HTTP MSE mode).
  - Ensure that carrier cell private IPs (`10.x.x.x`) are **100% ignored and never displayed** under any circumstances, falling back to a standby local detection message if no valid local Wi-Fi or tethering IP is available.

---

## Proposed Changes

### 2. NestJS Backend Workspace (`c:\project\tesla_manager`)

We will add a new controller and service inside NestJS (`tesla_manager/manager`) to securely store and retrieve smartphone IP addresses isolated by `userId` in an in-memory `Map`.

#### [NEW] [castla.controller.ts](file:///c:/project/tesla_manager/manager/src/tesla/castla.controller.ts)
- Add HTTP endpoints under `/api/castla`:
  - `POST /api/castla/register-ip`: Endpoint for the Android app to register `{ userId: string, ip: string }`.
  - `GET /api/castla/get-phone-ip`: Endpoint for the Svelte 5 frontend to query the local IP for a specific `userId` (e.g., `?userId=xxx`).

#### [NEW] [castla.service.ts](file:///c:/project/tesla_manager/manager/src/tesla/castla.service.ts)
- Implement an in-memory `Map<string, string>` where:
  - Key: `userId` (unique identifier)
  - Value: `phoneIp` (local private IP)
- Add methods `registerIp(userId: string, ip: string)` and `getPhoneIp(userId: string): string | null`.

#### [MODIFY] [tesla.module.ts](file:///c:/project/tesla_manager/manager/src/tesla/tesla.module.ts)
- Import and declare `CastlaController` and `CastlaService` inside `TeslaModule` to wire them up under the existing `/api` routing path of the server.

---

### 3. Nginx Configuration (`c:\project\tesla_manager`)

We will provide modifications to proxy `/api/castla` requests and serve Svelte 5 Static Viewer files under `/castla`.

#### [MODIFY] [car.conf](file:///c:/project/tesla_manager/nginx/car.conf)
- Add a static routing rule for Svelte 5 viewer files:
  ```nginx
  location /castla {
      alias /var/www/castla/viewer/dist; # Path to Svelte 5 built static directory
      try_files $uri $uri/ /castla/index.html;
      index index.html;
  }
  ```
- Make sure existing `/api/` proxy rules correctly forward to the NestJS port (`http://127.0.0.1:5300`), which will automatically cover `/api/castla/...`.

---

### 4. Svelte 5 Frontend Workspace (`c:\project\tesla_manager`)

We will create a new route in Svelte 5 (`tesla_manager/viewer`) to handle user-isolated HTTPS/WebCodecs and HTTP/MSE hybrid playback tracks.

#### [NEW] [castla/+page.svelte](file:///c:/project/tesla_manager/viewer/src/routes/castla/%2Bpage.svelte)
- Create a modern, beautiful UI for the viewer page:
  - Extract the `userId` parameter from the URL query string (e.g. `?userId=xxx`).
  - Fetch the local IP from the backend API: `GET /api/castla/get-phone-ip?userId=xxx`.
  - **Dynamic Protocol Switching Logic**:
    - **Secure Context (HTTPS)**: If `window.isSecureContext` is true (loaded via `https://car.fbezita.com`), format the WebSocket URL as:
      `wss://[phoneIp-with-dashes].ip.fbezita.com:9090/stream` (e.g., `wss://192-168-43-1.ip.fbezita.com:9090/stream`).
      - Parse and decode incoming H.264 video frame chunks using **WebCodecs (`VideoDecoder` API)** and render directly to an HTML5 `<canvas>` element for low-latency, hardware-accelerated playback.
    - **Insecure Context (HTTP Local Fallback)**: If loaded directly via local IP (e.g., `http://192.168.43.1:9090`), format the WebSocket URL as:
      `ws://[phoneIp]:9090/stream`
      - Initialize **`jmuxer` and MSE (Media Source Extensions)** pipelines to demux and play the H.264 stream within an HTML5 `<video>` element.

---

## Verification Plan

### Automated/Local Tests
1. **IP Detection Validation**: 
   - Compile Android application and verify in Logcat that `NetworkMonitor` correctly filters out any `10.x.x.x` cell IPs, identifies local interfaces (`swlan0`, `ap0`, `wlan0`), and resolves to correct `192.168.x.x` ranges.
2. **NestJS API Validation**:
   - Make HTTP test requests (using Postman or curl) to `POST /api/castla/register-ip` and `GET /api/castla/get-phone-ip` to verify in-memory map storing and retrieval.
3. **Frontend Connection Resolution**:
   - Open the Svelte viewer at `/castla?userId=testUser` and check that the WebSocket address is correctly resolved as `wss://192-168-43-1.ip.fbezita.com:9090/stream` or `ws://192.168.43.1:9090/stream` depending on the protocol.
