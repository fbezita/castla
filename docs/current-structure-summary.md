# Castla Current Structure Summary

Last updated: 2026-05-26

This document is a handoff summary for the next refactoring pass.

It focuses on the code paths that are actually live today, the failure pattern reproduced in logs, the mitigations already applied, and the structural changes that now look necessary.

## Project Intent

Castla is a remote Android workspace compositor, not simple phone mirroring.

The intended ownership model is still:

- Android owns VirtualDisplays, encoder lifecycle, app/task placement, and MotionEvent injection.
- The browser owns split composition, launcher UX, pane decoder lifecycle, and input shaping before packets are sent.
- Layout is browser-authored and Android-interpreted.
- Pane-local stream recovery is preferred over broad session resets.

## Live Runtime Entry Points

The production path is still centered on the service monolith plus the Svelte frontend runtime.

Android:

- `app/src/main/java/com/castla/mirror/service/MirrorForegroundService.kt`
- `app/src/main/java/com/castla/mirror/server/MirrorServer.kt`
- `app/src/main/java/com/castla/mirror/server/ControlSocket.kt`
- `app/src/main/java/com/castla/mirror/capture/VirtualDisplayController.kt`
- `app/src/main/java/com/castla/mirror/input/TouchInjector.kt`
- `app/src/main/java/com/castla/mirror/service/AdaptiveBitrateManager.kt`

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

## Current Android Structure

`MirrorForegroundService.MirroringPipeline` currently owns too many responsibilities at once:

- VirtualDisplay creation, resize, rebuild, and release
- encoder and surface lifecycle
- touch injector attachment
- app launch and app restore
- display wakeup and keyframe nudges
- fallback and self-healing hooks
- app-mounted verification

Important service-wide pieces:

- `vdRequestChannel` + `startVdHardwareWorker()` serialize hardware rebuild work
- `MirrorServer` exposes control/video channels and callbacks
- `ControlSocket` routes browser messages into service listeners
- `AdaptiveBitrateManager` and other policy managers can still trigger rebuilds

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

## Rebuild Path Today

This is the most important current problem.

Rebuilds can still be triggered from multiple places:

- `MirroringPipeline.onViewportChange(...)`
- app launch preparation / self-healing branches
- display density listener
- codec/profile change handling
- `AdaptiveBitrateManager.applyPipelineScale(...)`
- fallback/recovery/self-healing branches inside `MirrorForegroundService`

Even though hardware execution is serialized through `vdRequestChannel`, **the requests themselves are still generated from many layers**.

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

- `MirrorForegroundService.requestRebuild(RebuildRequest)` is the central coordinator before requests enter `vdRequestChannel`
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

### 2026-05-27 IME & Service Cleanup Handoff

The virtual IME lifecycle stabilization and native binder thread crashes are now fully resolved.

#### 1) Virtual IME & Google Maps Search Overlay Dismiss Fix
- **Sender Unification**: `tapOutside` is now sent solely from `gestureFocusListener` on the frontend (`App.svelte`) upon viewport touch `pointerdown` inside a user gesture context. Sending `tapOutside` during `on:blur` was removed to prevent duplicate triggers.
- **Coordinates Reversion**: The `tapOutside` WebSocket protocol has been reverted to a plain, coordinate-less message `{ type: "ime", op: "tapOutside" }` to guarantee complete interface compatibility and prevent compile-time type mismatches on Android.
- **Stale-Proof Focus Check & Back Fallback Nudge**:
  - Tapping outside the search box immediately blurs the hidden `imeProxy` and sends `tapOutside`.
  - On the Android backend, since the focus clears before the message is received, `AccessibilityFocusState` is queried to check if the editor had editable focus within the last `1.5 seconds` (`isEditableFocusedRecently(1500L)`).
  - If so, it restores the user's default keyboard silently and injects `KEYCODE_BACK` (`input keyevent 4`) after a `250ms` delay, cleanly dismissing custom/translucent search overlays like Google Maps. Normal map scrolls do not trigger this.
- **Svelte Compile Restored**: Corrected Vite production compile block inside `App.svelte` by resolving mismatched curly braces inside the `blur` event handler's `setTimeout` scope.

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


