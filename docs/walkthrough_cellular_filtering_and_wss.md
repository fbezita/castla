# Walkthrough: Static URL Guidance & Automatic Public IP Correlation

We have successfully refined the signaling and pairing system to enable a seamless, zero-configuration user experience. The Android application now guides the user with a completely clean static URL, while the Svelte frontend and NestJS backend automatically match and pair the device and viewer sessions through public IP correlation.

---

## ⚙️ 1. Accomplishments & Refinements

### 🟢 Android App (`c:\project\castla`)
- **Static URL Guidance**: Updated `updateServerUrl()` in [MainActivity.kt](file:///c:/project/castla/app/src/main/java/com/castla/mirror/MainActivity.kt) so that when WebCodecs is enabled, `serverUrl` is strictly set to **`https://car.fbezita.com/castla`** (no query parameters shown).
- **Background Registration Preserved**: The background signaling registration still safely registers the local IP under the phone's unique `ANDROID_ID` using `POST /api/castla/register-ip`.

### 🔵 NestJS Signaling Backend (`c:\project\tesla_manager/manager`)
- **Dual-Map Storage Structure**: Refactored [castla.service.ts](file:///c:/project/tesla_manager/manager/src/tesla/castla.service.ts) to store and manage both:
  1. `userId -> localIp` map
  2. `publicIp -> localIp` map
- **HTTP Request Public IP Extraction**: Updated [castla.controller.ts](file:///c:/project/tesla_manager/manager/src/tesla/castla.controller.ts) to capture the client's public IP from incoming requests (`x-forwarded-for`, `cf-connecting-ip`, or `req.ip` remote address socket) and supply it to the service for registration and lookups.
- **Intelligent IP Resolution Fallback**: Enhanced lookup logic so that if the query contains no `userId` or uses the default placeholder, the backend looks up the browser's public IP in the `publicIpMap` and returns the matching dynamic local IP address.

### 🟡 Svelte 5 Viewer Web App (`c:\project\tesla_manager/viewer`)
- **Dynamic Lookup Error/Diagnostic Messages**: Updated [castla/+page.svelte](file:///c:/project/tesla_manager/viewer/src/routes/castla/%2Bpage.svelte) to dynamically omit `userId` from user-facing error messages when the default automatic lookup is active, keeping diagnostics clean and professional.

---

## 💻 2. File Diffs

### Android MainActivity
```diff
     private fun updateServerUrl() {
         if (streamSettings.webCodecsEnabled) {
-            serverUrl = "https://car.fbezita.com/castla?userId=${getUserId()}"
+            serverUrl = "https://car.fbezita.com/castla"
             return
         }
```

### NestJS CastlaService
```diff
 export class CastlaService {
   private readonly ipMap = new Map<string, string>();
+  private readonly publicIpMap = new Map<string, string>();
 
-  registerIp(userId: string, ip: string): { success: boolean; userId: string; ip: string } {
-    this.ipMap.set(userId, ip);
-    return { success: true, userId, ip };
+  registerIp(userId: string, ip: string, publicIp: string): { success: boolean; userId: string; ip: string; publicIp: string } {
+    if (userId && userId !== 'default_user' && userId !== 'unknown') {
+      this.ipMap.set(userId, ip);
+    }
+    if (publicIp && publicIp !== 'unknown') {
+      const normalizedPublicIp = this.normalizeIp(publicIp);
+      this.publicIpMap.set(normalizedPublicIp, ip);
+    }
+    return { success: true, userId, ip, publicIp };
   }
 
-  getPhoneIp(userId: string): string | null {
-    return this.ipMap.get(userId) || null;
+  getPhoneIp(userId?: string, publicIp?: string): string | null {
+    // 1. Try to find by userId if valid
+    if (userId && userId !== 'default_user' && userId !== 'unknown') {
+      const ip = this.ipMap.get(userId);
+      if (ip) return ip;
+    }
+
+    // 2. Try to find by publicIp
+    if (publicIp && publicIp !== 'unknown') {
+      const normalizedPublicIp = this.normalizeIp(publicIp);
+      const ip = this.publicIpMap.get(normalizedPublicIp);
+      if (ip) return ip;
+    }
+
+    return null;
+  }
+
+  private normalizeIp(ip: string): string {
+    if (ip.startsWith('::ffff:')) {
+      return ip.substring(7);
+    }
+    return ip;
   }
 }
```

### NestJS CastlaController
```diff
-  @Post('register-ip')
-  async registerIp(
-    @Body('userId') userId: string,
-    @Body('ip') ip: string,
-  ) {
-    if (!userId || !ip) {
-      throw new BadRequestException('userId and ip are required');
-    }
-    return this.castlaService.registerIp(userId, ip);
-  }
-
-  @Get('get-phone-ip')
-  async getPhoneIp(@Query('userId') userId: string) {
-    if (!userId) {
-      throw new BadRequestException('userId is required');
-    }
-    const ip = this.castlaService.getPhoneIp(userId);
-    if (!ip) {
-      throw new NotFoundException(`No IP address registered for userId: ${userId}`);
-    }
-    return { ip };
-  }
+  @Post('register-ip')
+  async registerIp(
+    @Body('userId') userId: string,
+    @Body('ip') ip: string,
+    @Req() req: Request,
+  ) {
+    if (!ip) {
+      throw new BadRequestException('ip is required');
+    }
+    const clientIp = this.getClientIp(req);
+    return this.castlaService.registerIp(userId || 'unknown', ip, clientIp);
+  }
+
+  @Get('get-phone-ip')
+  async getPhoneIp(
+    @Query('userId') userId: string,
+    @Req() req: Request,
+  ) {
+    const clientIp = this.getClientIp(req);
+    const ip = this.castlaService.getPhoneIp(userId, clientIp);
+    if (!ip) {
+      throw new NotFoundException('No IP address registered for this device or session');
+    }
+    return { ip };
+  }
```

### Svelte Page
```diff
     try {
       const response = await fetch(`/api/castla/get-phone-ip?userId=${userId}`);
       if (!response.ok) {
-        throw new Error(`Failed to retrieve phone IP. Please ensure the Castla app is started on your phone with userId: ${userId}`);
+        throw new Error(userId && userId !== 'default_user' 
+          ? `Failed to retrieve phone IP. Please ensure the Castla app is started on your phone with userId: ${userId}`
+          : 'Failed to retrieve phone IP. Please ensure the Castla app is started on your phone.');
       }
```

---

## 📊 3. Verification & Testing

1. **URL Display Verification**: Open Android Castla, enable WebCodecs under settings, start mirroring service. The Compose UI server URL reads exactly `https://car.fbezita.com/castla`.
2. **Registration and Public IP resolution**:
   - Android client registers its IP address. Backend receives request, extracts client public IP (e.g. `203.0.113.1`), and saves `203.0.113.1 -> 192.168.43.1`.
   - Browser on the same network requests `/api/castla/get-phone-ip` without passing custom query details.
   - Backend recognizes browser request from `203.0.113.1` (same public IP), matches and successfully returns `{ ip: "192.168.43.1" }`.
   - Dynamic WSS secure connection `wss://192-168-43-1.ip.fbezita.com:9090/stream` establishes instantly on Svelte front-end!
