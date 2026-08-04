# Castla Current Structure Summary

Last updated: 2026-06-02

This document is a handoff summary for the next refactoring pass.

It focuses on the code paths that are actually live today, the failure pattern reproduced in logs, the mitigations already applied, and the structural changes that now look necessary.

## Project Intent

Castla is a remote Android workspace compositor, not simple phone mirroring.

The intended ownership model is still:

- Android owns VirtualDisplays, encoder lifecycle, app/task placement, and MotionEvent injection.
- The browser owns split composition, launcher UX, pane decoder lifecycle, and input shaping before packets are sent.
- Layout is browser-authored and Android-interpreted.
- Pane-local stream recovery is preferred over broad session resets.

## Current Migration Summary

The current production path reflects a broader input/control migration, not just a small bug-fix pass:

- **Accessibility-driven input control -> IME architecture**: remote text control is now centered on IME lifecycle events instead of accessibility-side focus heuristics.
- **Accessibility-dependent focus detection removed from the live path**: Castla no longer relies on viewport dismiss inference or accessibility fallback logic to decide whether remote editing is active.
- **IME session-based focus tracking is active**: `sessionId`-aware `onStartInput`, `onFinishInput`, and `androidFocusChanged` signals now drive editable focus state.
- **Remote editable state synchronization exists**: frontend and Android coordinate explicit editable/focus state rather than inferring dismiss intent from viewport taps.
- **Focus recovery and input routing are simpler**: local input bypass remains, stale-event protection remains, but dismiss-specific gesture guards and related fallback behavior are gone.
- **Maps interaction stability improved**: Google Maps drag/pan is no longer exposed to outside-tap misclassification.
- **Mirroring restart stale-state protection exists**: first launch after restart performs fresh launch preparation so stale display/stream/launch state does not block Maps or same-app launch preparation.
- **Native Android IME on trusted VirtualDisplay is now the preferred path**: Samsung Keyboard / Gboard can render inside the Shizuku-created trusted VD with local IME policy, and this path is now preferred over the Castla IME proxy.
- **Castla IME proxy remains fallback-only**: the proxy text bridge and router remain in the codebase for fallback scenarios, but they are no longer the intended primary typing path when native VD IME is available.
- **Verbose diagnostics are runtime-toggled**: high-frequency Android/frontend diagnostic logs are off by default and can be enabled at runtime from settings.

## Live Runtime Entry Points

The production path is centered on `MirrorForegroundService` as a thin orchestration host, extracted lifecycle coordinators, `MirrorServer`, and the Svelte frontend runtime.

Android:

- `app/src/main/java/com/castla/mirror/service/MirrorForegroundService.kt`
- `app/src/main/java/com/castla/mirror/server/MirrorServer.kt`
- `app/src/main/java/com/castla/mirror/server/ControlSocket.kt`
- `app/src/main/java/com/castla/mirror/capture/VirtualDisplayController.kt`
- `app/src/main/java/com/castla/mirror/input/TouchInjector.kt`
- `app/src/main/java/com/castla/mirror/service/AdaptiveBitrateManager.kt`
- `app/src/main/java/com/castla/mirror/service/BrowserSessionCoordinator.kt`
- `app/src/main/java/com/castla/mirror/service/VirtualDisplayRebuildCoordinator.kt`
- `app/src/main/java/com/castla/mirror/service/RemoteInputCoordinator.kt`
- `app/src/main/java/com/castla/mirror/service/DisplayRoutingDiagnostics.kt`
- `app/src/main/java/com/castla/mirror/server/StreamSessionCoordinator.kt`
- `app/src/main/java/com/castla/mirror/server/ServerTlsConfigurator.kt`
- `app/src/main/java/com/castla/mirror/server/ServerHttpContent.kt`

Frontend:

- `frontend/src/App.svelte`
- `frontend/src/runtime/StreamRuntime.ts`
- `frontend/src/components/ViewportHost.svelte`
- `frontend/src/components/ViewportPane.svelte`
- `frontend/src/touch/TouchRouter.ts`
- `frontend/src/transport/ControlTransport.ts`
- `frontend/src/transport/VideoTransport.ts`

Built frontend assets are copied to:

- `app/src/main/assets/web`

The `app/src/main/java/com/castla/mirror/compositor/` tree still exists, but the live orchestration path is still `MirrorForegroundService.MirroringPipeline`.

## Extracted Runtime Boundaries

`MirrorForegroundService` remains the Android lifecycle owner, but these responsibilities are no longer implemented in the service body:

| Component | Responsibility |
| --- | --- |
| `BrowserSessionCoordinator` | browser connect/disconnect grace, layout visibility, and teardown |
| `VirtualDisplayRebuildCoordinator` | rebuild coalescing, touch deferral, stale filtering, and serialized hardware execution |
| `RemoteInputCoordinator` | fallback Castla IME focus, composition, and key injection |
| `DisplayRoutingDiagnostics` | IME/display routing dumps and diagnostics |
| `EncoderLifecycleCoordinator` | encoder release, recreation, stream generation, and keyframe wakeup |
| `StreamSessionCoordinator` | per-channel generation, first-frame readiness, and metadata replay |
| `ServerTlsConfigurator` | certificate refresh, PKCS12 verification, and TLS context creation |
| `ServerHttpContent` | app list/icon API and embedded frontend assets |

The preferred text-input path remains the native Android IME inside the trusted VD. `RemoteInputCoordinator` is retained as a fallback boundary and is gated by `RemoteInputPolicy`.
## Current Android Structure

`MirrorForegroundService.MirroringPipeline` currently owns too many responsibilities at once:

- VirtualDisplay creation, resize, rebuild, and release
- encoder and surface lifecycle
- touch injector attachment
- app launch and app restore
- display wakeup and keyframe nudges
- fallback and self-healing hooks
- app-mounted verification

### Current Launch / Restart Stability Policy

The current live policy is intentionally conservative:

- **No automatic app relaunch during recovery**: watchdog, keyframe recovery, touch-focus recovery, decoder recovery, and rebuild recovery must not force-stop or relaunch apps.
- **Fresh launch preparation exists**: after browser reconnect, mirroring restart, or pipeline restart, each pipeline marks `requiresFreshLaunchPreparation = true`.
- **First launch after restart is special**:
  - stale launch/display/stream state is cleared
  - cached SPS/PPS is cleared
  - stream metadata is reset to `streamReady=false / firstFrameReady=false` before the next generation begins
  - same-app launch dedupe is bypassed once so the first app after restart gets a fresh VD/display/stream binding even if it matches the previous package
- **Soft recovery only**:
  - wake display
  - request keyframe / IDR
  - replay cached SPS/PPS if available
  - focus nudge where applicable
- **No `restoreContent()` relaunch policy**: rebuild and recovery paths no longer use app relaunch as a generic repair tool.

Important service-wide pieces:

- `VirtualDisplayRebuildCoordinator` serializes hardware rebuild work through a bounded 16-entry FIFO channel; producers suspend when the queue is full instead of growing memory without limit
- `MirrorServer` exposes control/video channels and callbacks
- `ControlSocket` routes browser messages into service listeners
- `AdaptiveBitrateManager` and other policy managers can still trigger rebuilds

### Current Native IME Policy

The live direction has shifted from "proxy-first" to "native IME first" when the privileged VD path is available.

- `PrivilegedService.createVirtualDisplay(...)` is the working creation path for native IME-in-VD.
- The trusted VD path applies:
  - `PUBLIC`
  - `PRESENTATION`
  - `OWN_CONTENT_ONLY`
  - `DESTROY_CONTENT`
  - `OWN_DISPLAY_GROUP`
  - `TRUSTED`
  - `ALWAYS_UNLOCKED`
- After VD creation and surface attachment, Castla also applies:
  - `setShouldShowSystemDecors(displayId, true)`
  - `setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_LOCAL)`
- Native app launch prefers `ActivityOptions.setLaunchDisplayId(...)` through the privileged binder path and only falls back to `am start --display ...` if necessary.
- `MirrorForegroundService` now treats native VD IME as the preferred mode and keeps the Castla IME proxy path as fallback.
- Samsung Keyboard can appear in split/general/floating layouts depending on Samsung Keyboard's own state. This layout choice is currently treated as IME-owned behavior, not Castla-controlled policy.

### Current Diagnostics Policy

Diagnostic noise was intentionally reduced after the native IME investigation.

- Core logs still remain on by default:
  - VD create / release
  - app launch path
  - IME policy application
  - build markers
  - hard errors
- Heavy diagnostic paths are now verbose-only:
  - repeated IME routing dumps
  - `dumpsys input_method` / `dumpsys window` snapshot parsing
  - frontend IME debug chatter
  - JMuxer per-frame / SourceBuffer verbose diagnostics
- The runtime switch is `verboseDiagnosticsEnabled` in `StreamSettings`.

## Current Frontend Structure

Frontend runtime responsibilities are split like this:

- `TouchRouter`
  - normalizes coordinates
  - coalesces and rate-limits `MOVE`
  - tracks gesture stats
  - sends `touch` packets over control websocket
- `ViewportHost`
  - is now the singleton pointer listener surface
  - maps pointer events to panes
  - owns split layout UI and layout flush timing
- `ViewportPane`
  - owns decoder lifecycle and stall watchdog
- `StreamRuntime`
  - owns control/video transports
  - tracks generations and pane health
  - currently still emits decoder recovery signals on frame rejection/stall

### Current Launcher Structure

The launcher path is no longer treated as a single Svelte monolith.

- `frontend/src/components/AppLauncher.svelte`
  - owns launcher orchestration, drag session lifecycle, drawer scroll lock/unlock, and final drop resolution
- `frontend/src/components/LauncherTabs.svelte`
  - owns top tabs, active tab highlight, and drag-time tab drop hints
- `frontend/src/components/AppRow.svelte`
  - owns compact card rows for `Auto Run`, `Starred`, and `Recent`
- `frontend/src/components/CategoryAccordion.svelte`
  - owns `Browse` category accordions and the denser app-list presentation
- `frontend/src/components/DragDropOverlay.svelte`
  - owns left/right/bottom dropzone visuals, trash guidance, and drag ghost rendering
- `frontend/src/components/PairDialog.svelte`
  - owns app-pair swap/dissolve editing UI

The launcher's gesture model is also stricter now:

- normal browsing remains native-scroll first
- long-press enters an explicit drag session
- only the active drag session locks global touch scroll
- tab hover changes visuals during drag, but actual starred/autorun mutation happens only on drop
- drawer auto-scroll runs on `requestAnimationFrame` rather than `pointermove` bursts

This means the live launcher UX is now intentionally split into two modes:

- **browse mode**: light, native-feeling scroll and denser app scanning
- **drag mode**: deterministic ghost, drawer-aware auto-scroll, and explicit drop targets

## Touch Path Today

Current path:

1. browser pointer event
2. `ViewportHost.handlePointer(...)`
3. `TouchRouter.pointer(...)`
4. `StreamRuntime.control.send({ type: 'touch', ... })`
5. `ControlSocket`
6. `MirrorServer.onTouchEvent(...)`
7. `MirrorForegroundService` touch listener
8. `TouchInjector`
9. `VirtualDisplayController.injectMotionEventWithResult(...)`
10. privileged `InputManager.injectInputEvent()`

### Frontend Touch Changes Already Applied

These were already done before the next refactor:

- singleton pointer listener structure in `ViewportHost`
- hard `MOVE` rate cap in `TouchRouter`
- frontend `MOVE` dedup/drop
- Android `MOVE` dedup/throttle safety net in `TouchInjector`
- reduced frontend logging so only `gesture complete` and warnings/errors remain useful

### Current Touch Conclusion

The original `MOVE flooding` problem was real, but it is no longer the main blocker.

Evidence from the latest logs:

- `movePacketsPerSecond` is now usually around `2..6`, not `15..22`
- `gesturePackets` per swipe are relatively stable, usually around `8..13`
- `inject_privileged durationMs` is usually small, often `0.5..1.5ms`

So the browser/input packet density problem has been reduced significantly.

## Decoder / Recovery Path Today

Frontend decoder health still feeds recovery behavior:

- `ViewportPane` runs a stall watchdog and can call `runtime.recoverPaneStream(pane)`
- `StreamRuntime.dispatchFrame(...)` can emit `frameRejected`
- `StreamRuntime.recoverRejectedStream(...)` can request a keyframe after repeated rejects

Recent mitigation applied:

- frame reject recovery is now less aggressive
- a single reject no longer triggers immediate recovery
- repeated rejects within a short window are required before `requestKeyframeAfterReject`

This reduced noisy recovery, but the path is still structurally coupled to Android rebuild behavior.

### Current Stream Generation Rules

The active generation model now assumes:

- every rebuild / restart begins a fresh stream generation
- `firstFrameReady` remains `false` until the first decoded frame of that generation is actually broadcast
- browser layout should remain pending until the generation's first frame is confirmed
- cached SPS/PPS is cleared before encoder rebuild and replayed again only from fresh encoder output or explicit keyframe request paths

## Rebuild Path Today

This is the most important current problem.

Rebuilds can still be triggered from multiple places:

- `MirroringPipeline.onViewportChange(...)`
- app launch preparation / self-healing branches
- display density listener
- codec/profile change handling
- `AdaptiveBitrateManager.applyPipelineScale(...)`
- fallback/recovery/self-healing branches inside `MirrorForegroundService`

Hardware execution is serialized through the bounded queue in `VirtualDisplayRebuildCoordinator`, while rebuild requests can still originate from multiple policy layers. Equivalent same-pane requests are coalesced within a short window before enqueue.

### Recent Mitigation Applied

`MirroringPipeline.rebuild(...)` now checks whether any pipeline has active touch interaction and defers enqueue for up to about `1.5s`.

This logs:

- `[InputTrace] touch_guard ... reason=rebuild_defer ...`

This is a guardrail, not a structural fix.

## Failure Pattern Confirmed in Logs

The latest logs point to a specific sequence:

1. repeated app switching across map/navigation apps
2. decoder/frame anomalies such as `frameRejected`
3. `secondary` and then `primary` rebuilds occur
4. touch in progress gets canceled or interrupted around rebuild
5. after rebuild / post-rebuild keyframe, fresh `DOWN` eventually becomes:
   - `inject_privileged ... result=false`

Important observation:

- the eventual failure is **not** preceded by high move throughput anymore
- the failure is strongly correlated with **rebuild/reconfigure windows**
- `inject_false_probe` still shows the correct app and focused display, but stale task/session traces remain on the same display id

Current leading hypothesis:

- touch input is being invalidated by display/window/task state churn during or right after rebuild
- multiple subsystems can currently request recovery/rebuild independently
- this creates racey interaction between stream recovery, display rebuild, and active touch injection

## What We Learned From the Latest Logs

These points should be treated as established for the next refactor:

1. The dominant issue is no longer raw browser `pointermove` volume.
2. `injectInputEvent()` failure can still happen even when move volume is low and inject durations are short.
3. The strongest correlation is:
   - `frameRejected` or other recovery pressure
   - followed by `rebuild_begin(...)`
   - followed by `injectInputEvent(...)=false`
4. The current architecture lets too many layers ask for rebuild directly.

## Why A Structural Refactor Is Now Justified

At this point, the problem is not one isolated bug.

The issue is that:

- input path
- decoder recovery
- layout transitions
- app launch/restore
- VirtualDisplay rebuild

are all partially entangled.

The code already has mitigation patches, but the logs suggest the real fix needs stronger separation of concerns.

## Refactoring Direction For The Next Session

The next refactor should aim for a single rebuild/recovery control plane.

### Target Direction

1. Separate **input session state** from **display recovery state**
2. Route all rebuild intents through one coordinator API
3. Prevent direct rebuild calls from decoder / thermal / viewport / codec handlers
4. Treat active touch as a first-class gate for rebuild approval

### Recommended First Refactor Step

Create a single rebuild request boundary such as:

- `requestRebuild(reason, priority, pane, width, height, options)`

and move policy into that boundary:

- reject or defer while touch is active
- coalesce duplicate requests
- apply cooldowns
- choose which requests are allowed during launch / recovery / split transitions

### Direct `rebuild()` Call Sites That Need To Be Collapsed

At minimum, inspect and route these through a central coordinator:

- `MirroringPipeline.onViewportChange(...)`
- service display density listener
- codec/profile switch logic
- `AdaptiveBitrateManager.applyPipelineScale(...)`
- fallback / self-healing / restore paths
- any decoder-recovery-triggered path that can eventually lead to rebuild

## Suggested State Separation

The next design pass should probably separate:

### Input state machine

- `Idle`
- `TouchActive`
- `Cancelling`
- `Rejected`

### Display / recovery state machine

- `Idle`
- `Launching`
- `Stable`
- `Recovering`
- `Rebuilding`
- `Suspended`

Then explicitly define allowed transitions such as:

- no rebuild while `TouchActive`
- decoder recovery may request keyframe while `TouchActive`
- decoder recovery may request rebuild only after touch ends

## Current Temporary Guards In Place

These are important so the next session does not accidentally remove them without replacement:

- frontend singleton pointer routing
- frontend hard `MOVE` cap
- frontend and Android `MOVE` dedup/drop
- touch-time fallback cancellation / skip
- `frameRejected` recovery throttling
- touch-time rebuild defer

They are tactical protections, not the final architecture.

## Handoff Summary

If the next session starts from one sentence, it should be this:

> The urgent structural problem is not raw move spam anymore; it is that decoder/layout/recovery code can still trigger rebuild pressure that collides with active touch and eventually corrupts the input path on the target VirtualDisplay.

### 2026-05-26 Refactor Pass Update

The first rebuild boundary has now been introduced on Android:

- `MirrorForegroundService.requestRebuild(RebuildRequest)` delegates to `VirtualDisplayRebuildCoordinator` before requests enter its bounded hardware queue
- direct rebuild callers in viewport, density, launch self-heal, adaptive bitrate, and thermal paths now route through `MirroringPipeline.requestRebuild(...)`
- rebuild requests now carry `reason` and `priority`
- the existing active-touch defer behavior has moved into the coordinator
- duplicate same-pane rebuild requests are coalesced over a short window before hardware enqueue

Remaining follow-up work:

1. inventorying every `rebuild(...)` caller
2. reducing direct cross-layer recovery triggers
3. promoting decoder recovery into explicit keyframe-first / rebuild-later policy
4. formalizing input and display recovery state machines

### 2026-05-26 Touch Fix Outcome

The hardreset touch freeze is now resolved.

Temporary hardreset tracing was used to isolate the real failure boundary and has since been
trimmed back out of the runtime path.

#### Root Cause Confirmed

The key issue was not browser reconnect, control transport, app launch routing, or display
focus drift.

The real problem was that browser `pointerId` values were being passed straight through into
Android `MotionEvent.PointerProperties.id`.

After repeated reload/hardreset cycles, the browser could send larger ids such as `36`, while
Android input injection remained happy with compact local ids such as `0`, `1`, `2`.

That mismatch explained the observed behavior:

- real user touches after hardreset could be rejected at `injectInputEvent(...)`
- synthetic internal nudges using small local ids could still succeed
- full reload often worked because the next browser pointer id happened to be small again

#### Fix Kept In The Runtime

`TouchInjector` now remaps browser pointer ids to Android-local pointer ids for the lifetime
of the active gesture state.

Current behavior:

- browser ids remain the external protocol ids
- Android injection uses compact local ids within the supported pointer range
- local ids are released on `UP`, `CANCEL`, and injector reset/release paths

This is the behavior-changing fix that should remain.

#### Cleanup Applied After Confirmation

The temporary hardreset investigation scaffolding is no longer considered part of the
intended structure:

- hardreset generation tagging on touch packets
- first-packet hardreset tracing
- dispatcher probe logging used during the hardreset investigation
- inject source tagging used only for diagnosis

The normal input and rebuild logs remain, but the special-case investigation logging has been
reduced again.

#### Current Touch Handoff

If the next session starts from one sentence, it should be this:

> The hardreset touch freeze was fixed by remapping browser pointer ids to Android-local pointer ids inside `TouchInjector`; the remaining touch protections worth keeping are the existing move dedup/throttle guard and the rebuild defer guard while touch is active.

### 2026-06-01 IME Simplification & tapOutside Removal

The `tapOutside` feature was intentionally removed from the system.

#### 1) Remote IME Policy
- **IME-first architecture**: remote text entry now follows IME lifecycle synchronization rather than accessibility-dependent focus inference.
- **No viewport-based dismiss inference**: Castla no longer tries to guess that a viewport tap means "dismiss the remote search box" or "exit text mode".
- **No `tapOutside` control message**: The frontend does not emit `{ type: "ime", op: "tapOutside" }` anymore.
- **Simplified FSM**: The frontend IME state machine now only keeps `IDLE`, `ANDROID_FOCUSING`, `READY`, `BLUR_PENDING`, and `RECOVERING`.
- **Focus acquisition only**: Viewport `pointerdown` is used only to help `imeProxy` focus acquisition under browser user-gesture rules.
- **Local input isolation remains**: Local browser inputs still bypass Android key forwarding and `imeProxy` focus stealing.
- **Session-driven editable sync**: remote editable state is synchronized through `androidFocusChanged`, `onStartInput`, `onFinishInput`, and stale-session protection.

#### 2) Stability Rationale
- **Maps drag/pan safety**: Google Maps drag/pan can no longer be misclassified as an outside-tap dismiss gesture because that entire path no longer exists.
- **No dismiss-side races**: Removing `tapOutside` also removes `requestHideSelf()` timing races, dismiss echo suppression, and cooldown/gesture bookkeeping.
- **No BACK fallback**: The old `KEYCODE_BACK`-based dismiss fallback is gone and must not be reintroduced.

#### 3) Android Backend Behavior
- **TapOutside listener removed**: `MirrorServer` / `ControlSocket` / `MirrorForegroundService` no longer carry a tapOutside listener path.
- **No dismiss-side IME forcing**: Android no longer calls `finishComposingText()` or `requestHideSelf()` in response to viewport taps.
- **Session-driven state only**: Remote IME focus/blur state is driven by normal `androidFocusChanged`, `onStartInput`, and `onFinishInput` lifecycle events.

### 2026-06-01 Restart / Maps First-Launch Stabilization

Mirroring restart now carries an explicit stale-state reset policy:

- **Fresh launch preparation on reconnect/restart**:
  - `currentApp` may still remember the previous target package, but the next launch is still treated as needing fresh preparation.
  - stale display affinity must not cause same-app launch short-circuiting on the first launch after restart.
  - cached target package / last-launched state must not suppress first-launch preparation after restart.
- **Maps-first launch protection**:
  - when mirroring is stopped and started again, the first Google Maps launch must receive a fresh display binding, keyframe/IDR request, SPS/PPS replay opportunity, and stream generation reset.
  - this is fixed without `force-stop`, `BACK`, or `am start` retry loops.
- **Warm-start path remains allowed**:
  - existing tasks may still be moved to the current VD/display
  - but the first post-restart launch bypasses stale same-app guards so preparation is not skipped.

#### 2) Synchronous Service onDestroy() Cleanup & Mutex Crash Prevention
- **Synchronous Cleanup Execution**: Refactored the asynchronous thread dispatch inside `onDestroy()` into a **synchronous sequential cleanup** (`performCleanup("service_ondestroy")`). This guarantees all native resource tear-downs are completed before the Android system terminates (SIGKILL) the service process.
- **Released Flag Recovery**: Encapsulated `executeReleaseInternal()` in a `try-finally` block ensuring that the `released` AtomicBoolean flag is reset back to `false` when release finishes. This prevents the pipeline from freezing in a stale released state upon hot reload, rebuild, or task restarts.
- **Codec / Encoder Thread Join**: 
  - Equipped `VideoEncoder` and `JpegEncoder` with delicate Atomic released flag boundaries and structured thread joins (`HandlerThread.join(2000)`).
  - This ensures that native MediaCodec and ImageReader resources are only destroyed *after* the async binder loop/drain threads have fully exited, permanently eliminating the `FORTIFY pthread_mutex_lock on destroyed mutex` signal crashes.

### 2026-05-27 Hot Restart Stream Recovery & SSL Teardown Pass

The hot restart stream recovery loop and embedded server SSL configurations have been streamlined.

#### 1) Bypassing Keyframe Throttling on Reconnect
- **Force Flag Propagation**: Modified `setKeyframeRequester` and `onKeyframeRequest` callbacks on Android to propagate a `force: Boolean` parameter.
- **Instant Keyframe Guarantee**: When a new video socket opens (`video_open`), `force = true` is passed, bypassing the 1000ms keyframe request throttle (`now - lastKeyframeRequestTime < 1000L`). This ensures the newly connected browser decoder receives a keyframe instantly without being locked out by in-flight layout updates.

#### 2) Relaxed Browser Frame Reject Interval
- **Low-FPS Tolerance**: Relaxed the interval checking window (`consecutiveFrameRejects`) in `StreamRuntime.ts` from 500ms to 3000ms.
- **Fail-Safe Pulling**: Even on static or low-fps screens, consecutive rejects now accumulate reliably without resetting, successfully pulling a fresh keyframe from Android within 3 seconds if the initial keyframe gets dropped.

#### 3) Autonomic Session Hard Reset on Server Reboot
- **Instance ID Change Tracking**: Integrated `instanceId` tracking inside `App.svelte` using the `"serverInit"` WebSocket message.
- **Self-Healing Refocus**: When the user stops and restarts the Android service (generating a fresh UUID `instanceId`), the frontend automatically detects the reboot and triggers a clean `hardReset("server_reboot")`. This resets Svelte stores, cleans up the decoder session, and brings up the clean Logo/Launcher home screen automatically without requiring manual browser refreshes (F5).

#### 4) Resolution-Preserving Hot Rebuild
- **Requested Dimension Priority**: Fixed the hot restart resolution bug where `onBrowserConnected` would forcefully rebuild the VirtualDisplay as a 720x720 display because layout dimensions were empty during early boot.
- **Aspect Ratio Alignment**: The early rebuild sequence now prioritizes client-requested sizes (`primary.requestedWidth/Height`) first, preventing unaligned viewports and display stretching on restart.

#### 5) Streamlined SSL/HTTPS & Keystore Teardown
- **Eliminated Keystore Overhead**: Completely removed all external cloud dynamic certificate download tasks (`triggerCertDownloadInBackground`) and static assets keystore loading (`castla.p12`).
- **Plain WS Performance Boost**: Streamlined NanoHTTPD to run strictly as a lightweight Plain HTTP and WebSocket (WS) server on port 9090. This eliminates SSL encapsulation/decapsulation overhead, minimizes latency, and removes certificate expiration runtime crashes while benefiting from the secure context provided by the HTTPS static host.


---

## 2026-05-28 Relay HTTPS / WebCodecs 안정화 업데이트

### 핵심 변경 사항
- Cloudflare 기반 relay hostname 자동 등록 기능 추가
- `deviceId → c-<deviceId>.castla.fbezita.com` 구조로 동적 relay endpoint 생성
- `MirrorServer.serverIp` 기반 실제 LAN IP publish 로직 적용
- HTTPS + WebCodecs secure context 경로 안정화
- HTTP(JMuxer) / HTTPS(WebCodecs) 모드 분리 검증 완료

### Relay DNS 흐름
1. MirrorServer 시작
2. `updateServerUrl()` 에서 실제 LAN IP 확보
3. `DeviceRelayDnsManager.publishCurrentIpIfNeeded()` 호출
4. backend relay API 등록
5. Cloudflare DNS 레코드 동적 생성
6. `https://c-<device>.castla.fbezita.com:9090` 접속 가능

### 중요 수정 사항
- 초기에는 `10.0.0.50` 같은 VPN/TUN 주소가 publish 되는 문제가 있었음
- 실제 미러 서버 접근 가능한 LAN IP (`192.168.x.x`) 기반으로 수정
- `serverIp == "0.0.0.0"` 상태에서는 publish skip guard 추가
- WebCodecs 비활성 상태에서는 relay publish 자체 skip 처리

### 확인 완료
- `nslookup c-<device>.castla.fbezita.com`
  → 실제 LAN IP 정상 반환
- `curl -vk https://c-<device>.castla.fbezita.com:9090`
  → HTTPS 서버 응답 정상 확인
- WebCodecs backend 정상 활성화 로그 확인:
  `backend=webcodecs secure=true`

### 추가 디버깅 결론
- `WebSocket is closed before the connection is established`
  초기 1회 경고는 실제 구조적 장애가 아니라:
  - 이전 세션
  - 중복 HTTP/HTTPS 탭
  - stale socket close
  등에 의해 발생 가능한 비치명적 초기 abort로 판단
- 실제 스트림 연결 및 미러링은 정상 동작 확인

### 현재 권장 운영 방식
- WebCodecs ON:
  - `https://castla.fbezita.com`
- WebCodecs OFF:
  - `http://<LAN-IP>:9090`
- HTTP/HTTPS 혼합 동시 접속 테스트는 피할 것


---

## 2026-05-29 WebCodecs Black Screen 및 안드로이드 안정화/자동화 업데이트

### 핵심 변경 사항
- **WebCodecs 블랙 스크린 해결**: 
  - `VideoDecoder.configure`에서 `description` 필드(SPS/PPS)를 과감하게 생략하여 디코더가 완벽히 `Annex-B` 바이트 스트림 모드로 작동하도록 유도.
  - 최초 키프레임(`keyFrame = true`)이 도달할 때 캐싱해둔 `configPayload`(SPS/PPS)를 키프레임 데이터 앞에 다이렉트로 결합하여 단일 Annex-B 청크로 주입하도록 `WebCodecsBackend.ts` 조치 완료.
- **안드로이드 Target SDK 34+ 보안 예외 예방**:
  - `enabled_input_methods` settings를 직접 읽으려 시도할 때 `SecurityException`이 뜨던 문제를 `InputMethodManager` API 우선 조회 및 settings Secure 폴백 이중 구조로 전환하여 박멸.
- **서비스 onDestroy/performCleanup MainThread 블로킹 방지**:
  - 메인 UI 스레드 상에서 `mirrorServer?.stop()`이 호출되었을 때 Conscrypt SSL 소켓 클로즈와 얽혀 `NetworkOnMainThreadException`이 발생하던 현상 해소.
  - `MirrorServer.stop()`을 오버라이딩하여 메인 스레드 호출 시 자동으로 백그라운드 스레드(`MirrorServerStopThread`)에서 모든 소켓 정지 프로세스를 실행하도록 구조화.
- **Shizuku 기반 접근성 서비스 100% 자동 바인딩 및 불필요 수동 UI 영구 삭제**:
  - Shizuku를 통해 접근성 서비스를 백그라운드로 켤 때, OS(`AccessibilityManagerService`)가 변경 사항을 강제 인식해 백그라운드 서비스를 런타임에 리로드 및 즉각 바인딩(Binding)할 수 있도록 `null/0 -> 재설정/1` 강제 상태 급변(State Churn) 시퀀스 쉘 패치 적용.
  - 이에 따라 수동으로 직접 껐다 켤 필요가 아예 없어졌으므로, UI 상에서 사용자 혼란을 유도하던 "Text Input Setup" 가이드 카드를 `MainActivity.kt` 의 컴포저블 코드 상에서 완전히 삭제하여 원터치 자동 UX 실현.


---

## 2026-05-29 Mixed-Hashing DNS 캐시 극복 및 테슬라 초미세 동기화 / Manifest 안정화

### 1. Mixed-Hashing 디바이스 ID 아키텍처 도입 (DNS 캐시 원천 차단)
* **문제 현상**: 동일 기기가 핫스팟/네트워크 변경으로 인해 사설 IP가 달라질 때(예: `192.168.43.100` ➔ `192.168.1.50`), 이전 릴레이 호스트명이 동일하게 유지되면 차량 브라우저의 이전 IP DNS 캐싱에 의해 새로운 릴레이 접속이 완전히 마비되는 현상.
* **처방**: `CastlaDeviceId.kt` 내부 `getDeviceId(context, ip)` 가 사설 IP도 매개변수로 수용하여 `ANDROID_ID + "_" + ip` 조합으로 가변 믹스 해시(10자리 16진수)를 도출하는 **Mixed-Hashing 아키텍처**를 수립.
* **효과**: 네트워크 IP가 변동되는 즉시 새로운 릴레이 엔드포인트(`c-<mixedDeviceId>.castla.fbezita.com`)가 고유 식별자로 발급되어, 차량 브라우저의 고질적인 로컬 DNS 캐시 마비를 완벽하게 회피 및 우회 성공.

### 2. 테슬라 브라우저 초미세 Live-Edge 동기화 및 인코더 안전 롤백
* **장애 실체**: PC 크롬 및 WebCodecs 모드와 달리, 오직 테슬라 임베디드 Chromium 브라우저(JMuxer/MSE 모드)에서 비디오 소스 버퍼가 새로 유입될 때 재생 헤드(`video.currentTime`)가 최신 버퍼 경계(`bufferedEnd`)를 스스로 추종하지 못하고 정체(Stall)되어 최초 기동 시 블랙스크린, 앱 전환 시 마지막 프레임 고착(Freeze) 버그가 유발됨.
* **처방**:
  * **안드로이드 인코더 원복**: `VideoEncoder.kt` 의 `KEY_REPEAT_PREVIOUS_FRAME_AFTER` 리피터 주기를 의심의 여지 없이 안전하고 가벼운 표준 안정 상태(`100_000` 마이크로초, 100ms)로 **완벽하게 롤백 복원**하여 모바일 기기 리소스 부하를 제거.
  * **초미세 Live-Edge 강제 동기화 (`JMuxerBackend.ts`)**: 고착 판단 임계 지연 한도를 기존 `1.5초`에서 **`0.3초`**로 초정밀 튜닝하고, 이를 넘어서는 오차가 발생하는 즉시 재생 헤드를 최신 버퍼 끝(`bufferedEnd`)으로 강제 점프(`currentTime = bufferedEnd`)시키는 트리거 이식.
  * **콘솔 로그 쓰로틀링 (Throttling)**: 정상 디코딩 버퍼 변동에 의한 콘솔 도배(Spam)를 억제하기 위해, 강제 점프 로그 및 `onStatus` 상태 콜백 전송 빈도를 **최소 3초당 1회**로 정밀 억제하는 차단막을 적용하여 콘솔 청정 상태 확보.

### 3. Compose 탑레벨 스코프 가이드 및 FQN 가독성 리팩토링
* **클래스 독립형 Composable 스코프 수정**: `MainActivity.kt` 외부의 독립 탑 레벨 함수인 `CastlaScreen` 컴포저블 내부에서 `this@MainActivity.resolveReachableMirrorIp` 에러 완치 ➔ Composable 인터페이스에 `reachableMirrorIp: String` 매개변수를 전격 이관하고, `MainActivity.setContent` 인스턴스 스코프에서 IP를 사전에 도출하여 투명하게 주입받도록 하는 **단방향 의존성 주입(Dependency Injection) Compose 정석 구조** 확립.
* **FQN(장황한 전체 패키지명) 단축**: `com.castla.mirror.network.CastlaDeviceId`와 같이 인라인으로 나열되던 FQN 호출부를 상단 임포트 정비(`import com.castla.mirror.network.CastlaDeviceId`)를 거쳐 가독성 높게 **`CastlaDeviceId.getDeviceId(...)`**의 최단 명세로 단축 정돈 완료.

### 4. Android OS 자동 백업(Auto Backup)에 의한 캐시 오염 차단
* **문제 현상**: 이전 디버그/릴리즈 테스트 시 스마트폰에 저장해둔 WebCodecs `disable` 설정 캐시가, 앱을 완전히 삭제하고 다시 재설치해도 안드로이드 OS의 백그라운드 클라우드 구글 백업 엔진에 의해 자동으로 복원 덮어쓰여 최초 구동 기본값이 짓밟히던 현상.
* **처방**: `app/src/main/AndroidManifest.xml` 파일의 application 블록 내에 **`android:allowBackup="false"`** 옵션을 박아 OS의 임의 복원 개입을 영구 차단. 
* **효과**: 일반적인 앱 업데이트 시에는 기존 설정이 100% 보존되면서도, 삭제 후 최초 재설치 시에는 조용히 복원되는 꼬인 캐시 데이터의 영향 없이 언제나 순수하고 투명한 코드 기본 활성 상태(`Enable`)로 시작하는 청정 상태 보장.

---

## 2026-05-31 초경량 헬스체크 게이트웨이 및 Sidedrawer 스크롤/드래그 UX 완치

### 1. 초경량 징검다리 헬스체크 게이트웨이 도입 (모바일 데이터 0바이트 소모)
* **문제 현상**: 이전에는 릴레이 기기를 자동으로 선택하여 리다이렉트하는 기능이 부재했거나, 강제 앱 종료 시 서버 측이 좀비 세션을 삭제하지 못해 죽은 기기로 연결을 시도하다 지연이 유발되었습니다.
* **처방**:
  - `castla.public.controller.ts` 전체를 전격 개편하여, 단일 릴레이 게이트웨이 렌더러(`renderGateway`)를 이식했습니다.
  - 차량의 테슬라 브라우저가 직접 핫스팟 내부 사설 도메인 주소(`https://c-<mixedId>.castla.fbezita.com:9090/health`)로 800ms 초고속 로컬 HTTP 헬스체크를 날리도록 설계했습니다.
  - 성공 시 즉시 뷰어로 무중단 전환, 실패(앱 강제 종료/방전 등) 시 1초 내에 **"Castla Offline (폰에서 기동해주세요)"** 오프라인 전용 수려한 가이드 화면(`renderNoActiveDevices`)으로 자동Fallback합니다.
  - 이로써 외부 망을 거치지 않는 로컬 통신을 실현하여 **폰의 모바일 데이터를 단 1바이트도 소모하지 않는 완벽한 데이터 제로 세이프티**를 달성했습니다.
  - `castla.service.ts` 의 `getActiveRelays()` 내에 **15분 만료(TTL) 필터 가드**를 장착하여, 기기의 오프라인 감지 누락 시에도 낡은 좀비 세션들이 목록에 누적되지 않도록 원천 세정했습니다.
  - **[게이트웨이 무한 로딩 및 브라우저 캐시 완치]**:
    - 자가 서명 SSL 인증서 미신뢰 및 사설 IP 라우팅 블랙홀로 인해 브라우저의 소켓 악수 단계에서 락이 걸려 `AbortController.abort()` 신호가 통하지 않고 검은 화면에 갇히던 결함을 완치했습니다. `1200ms` 만료 시 무조건 오프라인 가이드 UI를 강제 노출하는 **이중 안전장치(Dual-Safe Fallback)**를 스크립트에 탑재하고, 1회 수동 예외 승인을 위한 **`수동으로 연결 (최초 접속 인증서 허용)`** A 태그 링크를 직관적으로 증설했습니다.
    - 브라우저(특히 테슬라 및 모바일 Chromium)가 302 리다이렉션과 게이트웨이 HTML 페이지 자체를 악질적으로 강력 로컬 캐싱하여, 새로운 코드로 배포한 뒤 F5를 눌러도 낡은 무한 펜딩 스크립트만 메모리에서 무한 호출되던 캐시 오염을 해소하기 위해, NestJS 진입 라우터 입구에 **`Cache-Control: no-store, no-cache, must-revalidate, max-age=0` 및 Pragma, Expires 3대 캐시 무력화 헤더를 주입**하여 매 접속 시 서버로부터 최신 HTML을 100% 강제 긁어오도록 완벽히 통제했습니다.

### 2. Sidedrawer 터치 스크롤 락 장애 및 드래그 UX 종합 완치
* **문제 현상**: 사이드 드로어 내에 앱 아이콘이 적체되어 늘어날 때, 위아래로 터치 및 마우스 휠 스크롤이 완전히 마비되는 현상과 더불어 드래그 시 우측 오버레이가 가려지는 UI 버그가 있었습니다.
* **처방**:
  - **터치 락 및 캡처 전면 제어**: `.split-app-item` CSS의 `touch-action: none;`을 `touch-action: pan-y;`로 교체하여 세로 스크롤을 전면 활성화했습니다. `pointerdown` 시 무조건 호출되던 `event.preventDefault()` 및 이른 포인터 캡처를 전격 삭제했습니다.
  - **롱프레스 반응성 및 취소 가드**: 롱프레스 임계 시간을 1초에서 `700ms`로 튜닝하여 경쾌한 진입 반응성을 제공하며, 손가락을 대고 10px 이상 쓸어내리는 즉시 롱프레스 타이머를 깨뜨려(`clearTimeout`) 세로 스크롤로 완벽하게 전환되는 취소 가드를 추가했습니다.
  - **롱프레스 영역 타이틀 포함 확장**: 앱 이름을 감싸던 `<button class="launch-main">` 태그를 `<div>` 태그로 전격 개선하고 `cursor: pointer` 스타일을 보완하여, 아이콘뿐만 아니라 앱 이름 영역을 꾹 눌러도 기분 좋은 진동과 함께 드래그앤드롭이 활성화되도록 수정했습니다.
  - **드래그 가이드 레이아웃 완치**: 드래그 시 드로어가 우측 가이드 존을 덮어버리던 레이아웃 버그를 `.drop-overlay`의 `z-index`를 `95`로 상향하여 해결했습니다. `pointer-events: none` 투과 규칙 덕분에, 드로어 위에 드롭 가이드 영역이 가려짐 없이 선명하고 무결하게 표출됩니다.
  - **[2차 긴급 완치] 롱클릭 해제 시 즉시 앱 기동 오작동 해결**:
    - 롱클릭(700ms)이 만료되어 정상적인 드래그 상태가 된 후에 사용자가 손가락을 떼도, 자식 `div.launch-main`에 `on:click`이 이중 바인딩되어 있어 브라우저가 추가로 발생시킨 터치 클릭 이벤트가 즉시 앱을 강제 구동시켜버리는 치명적 레이스 컨디션 결함을 적발했습니다.
    - **해결**: 앱 기동(`activateApp`)은 이미 부모 `.split-app-item` 엘리먼트의 포인터 업(`endPress`) 루틴에서 숏클릭 시에만 수행되도록 완벽히 가드되어 있으므로, 자식 `div`의 중복 `on:click|stopPropagation`을 **완전히 영구 삭제**했습니다.
  - **[3차 최종 완치] 누르고 스크롤 시 앱 즉각 오기동 차단 (pointercancel 격리 완치)**:
    - 터치 다운 후 슥 밀어 네이티브 스크롤이 시작되는 즉시 브라우저가 **`pointercancel`** 이벤트를 쏴서 터치 세션을 종료합니다. 이 이벤트가 `pointerup`과 혼선되어 `endPress`를 태우면서, 미처 움직이기 전이라 숏클릭 조건을 타서 앱이 즉시 실행되던 마지막 논리 구멍을 적발했습니다.
    - **해결**: 앱 기동 분기를 100% 원천 제거하고 오직 안전한 초기화만 실행하는 **`cancelPress` 전용 취소 핸들러**를 별도 신설하고, 마크업에 `on:pointercancel={cancelPress}`로 엄격 격리 교체 매핑했습니다.
    - **반응성 10px 복원**: 캔슬 오작동이 원천 봉쇄됨에 따라 흔들림 임계치를 기분 좋고 예민한 표준 **`10px`**로 돌려놓아 완벽한 스위프 및 드래그앤드롭 감도를 이룩했습니다.

## 2026-08-02 Virtual Display Task Routing and Encoder Lifecycle Update

### Current app launch policy

`MirrorForegroundService.MirroringPipeline.launchComponent()` now routes an app using the target VirtualDisplay rather than package-global task presence:

1. No matching task on the target VD: launch a new task on that display.
2. A matching task exists on the target VD: move that task to the front without launching a new activity.
3. A task exists only on another display: keep the target VD as the launch destination and create the target-display task when required.
4. A forced cold start: use the existing force-stop/new-task path.

The task decision is centralized in `LaunchPlanner`. Native `moveTaskToFront` is preferred through the privileged Binder service; the shell command fallback is retained for older Android/One UI releases.

### Display session and encoder policy

`DisplayLaunchSession` separates launch preparation from task routing. `DisplaySizePolicy` is the single source for the effective VD/encoder size:

- apply the pipeline maximum-height constraint;
- preserve aspect ratio when the height is capped;
- align both dimensions to the 16-pixel encoder boundary;
- enforce the 320-pixel minimum hardware boundary.

A size change rebuilds the encoder surface and starts a new stream generation. The lifecycle is release -> create -> attach the surface to the VD -> begin stream generation -> start encoder -> request a keyframe. Same-size task reuse does not rebuild the encoder.

### Compatibility and verification

The task query and privileged ActivityTaskManager calls remain reflection-based to tolerate signature differences between Android/One UI versions. The current One UI 9 verification covers new-task launch, same-VD task reuse, multi-app switching, resolution changes, and encoder reconnection. One UI 8.5 remains a pending device regression check.

## 2026-08-02 Screen-Off Mirroring Recovery

### Native service

MirrorForegroundService는 기본 Display의 interactive/state 변화를 조기 감시하고, 화면 OFF 직전에 freezeVideo를 서버에 전달합니다. SCREEN_ON이 빠르게 도착하는 One UI 환경에서는 실제 Display.STATE_ON을 확인할 때까지 최대 2초간 재시도합니다.

WAKE_PULSE_RELATED 이벤트는 자동 keep-alive/revive pulse 직후의 SCREEN_ON으로 추정되는 이벤트입니다. 이 경우 상태를 BLACKOUT_ACTIVE로 유지하고 panel-off 재시도는 하지 않으며, 영상만 안정화 후 재개합니다. 사용자 복귀는 USER_PRESENT 또는 사용자 분류 SCREEN_ON에서 ACTIVE로 전환됩니다.

### Server and frontend

MirrorServer.videoFrozen gate는 freeze 상태 동안 인코더 프레임의 WebSocket broadcast를 차단합니다. 복귀 시 keyframe을 요청해 디코더가 정상 프레임부터 재개하도록 합니다.

WebCodecsBackend는 마지막 정상 canvas 프레임을 보존하고, 감광/근검 프레임을 즉시 화면에 반영하지 않습니다. ViewportPane의 재연결 오버레이는 isConnected == false인 실제 연결 장애에만 표시됩니다.
### 2026-08-04 Coordinator and Build Determinism Update

- browser lifecycle, remote input, display diagnostics, VD rebuild scheduling, TLS, stream metadata, and HTTP content now have explicit runtime boundaries
- the VD rebuild queue is bounded to 16 pending requests and applies coroutine backpressure instead of using `Channel.UNLIMITED`
- equivalent rebuild requests are evaluated by the pure `RebuildRequestPolicy`
- native VD IME remains primary; proxy handling is explicitly gated by `RemoteInputPolicy`
- frontend builds use the latest commit SHA that changed `frontend/` unless `CASTLA_BUILD_TIMESTAMP` is explicitly provided, so identical sources produce identical asset hashes
- regression tests cover stream generation/metadata replay, rebuild queue/coalescing policy, native IME proxy gating, browser layout, and disconnect policy
