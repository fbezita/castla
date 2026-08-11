# Castla 현재 구조 요약

최종 갱신: 2026-08-11

이 문서는 다음 리팩터링 작업을 위한 인수인계 요약입니다. 현재 실제로 동작하는 코드 경로, 로그로 재현된 장애 패턴, 이미 적용한 완화책, 앞으로 필요한 구조 변경을 중심으로 정리합니다.

## 프로젝트 목표

Castla는 단순한 휴대폰 화면 미러링이 아니라 원격 Android 작업 공간 컴포지터입니다.

책임 모델은 다음과 같습니다.

- Android는 VirtualDisplay, 인코더 수명 주기, 앱/Task 배치, MotionEvent 주입을 담당합니다.
- 브라우저는 분할 화면 합성, 런처 UX, 뷰포트별 디코더 수명 주기, 패킷 전송 전 입력 보정을 담당합니다.
- 레이아웃은 브라우저가 결정하고 Android가 해석합니다.
- 전체 세션 초기화보다 뷰포트 단위 스트림 복구를 우선합니다.

## 현재 마이그레이션 요약

현재 운영 경로에는 단순 버그 수정을 넘어선 입력·제어 구조 변경이 반영되어 있습니다.

- **접근성 기반 입력 제어에서 IME 구조로 전환**: 원격 텍스트 제어는 접근성 포커스 추정 대신 IME 수명 주기 이벤트를 중심으로 동작합니다.
- **접근성 의존 포커스 감지 제거**: 원격 편집 활성 여부를 뷰포트 탭이나 접근성 fallback으로 추정하지 않습니다.
- **IME 세션 기반 포커스 추적**: `sessionId`를 인식하는 `onStartInput`, `onFinishInput`, `androidFocusChanged`가 편집 포커스 상태를 결정합니다.
- **명시적 편집 상태 동기화**: 프런트엔드와 Android가 편집·포커스 상태를 직접 동기화합니다.
- **단순화된 포커스 복구와 입력 라우팅**: 로컬 입력 우회와 stale 이벤트 방지는 유지하되 dismiss 전용 제스처 가드는 제거했습니다.
- **지도 조작 안정화**: Google Maps 드래그/팬이 외부 탭 dismiss로 오인되지 않습니다.
- **미러링 재시작 stale 상태 방지**: 재시작 후 첫 실행은 새 VD·스트림 연결을 준비하므로 이전 상태가 지도나 동일 앱 실행을 막지 않습니다.
- **신뢰 VD의 네이티브 Android IME 우선**: Shizuku로 생성한 trusted VD 안에서 Samsung Keyboard/Gboard를 표시하는 경로를 Castla IME proxy보다 우선합니다.
- **Castla IME proxy는 fallback 전용**: 네이티브 VD IME를 사용할 수 없는 경우에만 사용합니다.
- **상세 진단은 런타임 선택 사항**: 고빈도 Android/프런트엔드 로그는 기본 비활성화이며 설정에서 켤 수 있습니다.

## 현재 런타임 진입점

운영 경로는 얇은 오케스트레이션 호스트인 `MirrorForegroundService`, 분리된 수명 주기 코디네이터, `MirrorServer`, Svelte 프런트엔드 런타임을 중심으로 구성됩니다.

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

빌드된 프런트엔드 자산은 다음 위치로 복사됩니다.

- `app/src/main/assets/web`

`app/src/main/java/com/castla/mirror/compositor/` 트리는 남아 있지만 실제 오케스트레이션 경로는 `MirrorForegroundService.MirroringPipeline`입니다.

## 분리된 런타임 책임 경계

`MirrorForegroundService`가 Android 수명 주기의 소유자이지만 다음 책임은 서비스 본문 밖으로 분리되어 있습니다.

| 구성 요소 | 책임 |
| --- | --- |
| `BrowserSessionCoordinator` | 브라우저 연결/해제 유예, 레이아웃 가시성, 종료 처리 |
| `VirtualDisplayRebuildCoordinator` | rebuild 병합, 터치 중 지연, stale 요청 필터링, 하드웨어 작업 직렬화 |
| `RemoteInputCoordinator` | fallback Castla IME 포커스, 조합, 키 주입 |
| `DisplayRoutingDiagnostics` | IME/display 라우팅 덤프와 진단 |
| `EncoderLifecycleCoordinator` | 인코더 해제·재생성, 스트림 generation, keyframe 깨우기 |
| `StreamSessionCoordinator` | 채널별 generation, 첫 프레임 준비 상태, 메타데이터 재전송 |
| `ServerTlsConfigurator` | 인증서 갱신, PKCS12 검증, TLS context 생성 |
| `ServerHttpContent` | 앱 목록/아이콘 API와 내장 프런트엔드 자산 |

선호하는 텍스트 입력 경로는 trusted VD 내부의 네이티브 Android IME입니다. `RemoteInputCoordinator`는 `RemoteInputPolicy`로 제한되는 fallback 경계로 유지합니다.

## 현재 Android 구조

`MirrorForegroundService.MirroringPipeline`은 현재 다음 책임을 함께 가지고 있습니다.

- VirtualDisplay 생성, 크기 변경, rebuild, 해제
- 인코더와 surface 수명 주기
- 터치 injector 연결
- 앱 실행과 복원
- display 깨우기와 keyframe 요청
- fallback 및 self-healing hook
- 앱 mount 확인

### 현재 실행·재시작 안정화 정책

현재 운영 정책은 의도적으로 보수적입니다.

- **복구 중 자동 앱 재실행 금지**: watchdog, keyframe, 터치 포커스, 디코더, rebuild 복구는 앱을 force-stop하거나 재실행하지 않습니다.
- **새 실행 준비 상태 사용**: 브라우저 재연결, 미러링 재시작, pipeline 재시작 후 `requiresFreshLaunchPreparation = true`로 표시합니다.
- **재시작 후 첫 실행 특별 처리**:
  - 오래된 launch/display/stream 상태 제거
  - 캐시된 SPS/PPS 제거
  - 다음 generation 전에 `streamReady=false / firstFrameReady=false`로 초기화
  - 동일 앱 중복 실행 방지를 한 번 우회해 새 VD/display/stream 연결 보장
- **soft recovery만 허용**:
  - display 깨우기
  - keyframe/IDR 요청
  - 가능한 경우 SPS/PPS 재전송
  - 필요한 경우 포커스 보정
- **`restoreContent()` 재실행 정책 제거**: rebuild와 복구가 일반적인 수리 수단으로 앱을 재실행하지 않습니다.

서비스 공통 핵심 요소는 다음과 같습니다.

- `VirtualDisplayRebuildCoordinator`는 최대 16개 FIFO 채널로 하드웨어 rebuild를 직렬화하며, 큐가 가득 차면 생산자를 중단해 무제한 메모리 증가를 막습니다.
- `MirrorServer`는 control/video 채널과 callback을 제공합니다.
- `ControlSocket`은 브라우저 메시지를 서비스 listener로 전달합니다.
- `AdaptiveBitrateManager` 등 정책 관리자는 coordinator를 통해 rebuild를 요청할 수 있습니다.

### 현재 네이티브 IME 정책

privileged VD를 사용할 수 있을 때는 proxy보다 네이티브 IME를 우선합니다.

- `PrivilegedService.createVirtualDisplay(...)` is the working creation path for native IME-in-VD.
- trusted VD 경로에는 다음 flag를 적용합니다.
  - `PUBLIC`
  - `PRESENTATION`
  - `OWN_CONTENT_ONLY`
  - `DESTROY_CONTENT`
  - `OWN_DISPLAY_GROUP`
  - `TRUSTED`
  - `ALWAYS_UNLOCKED`
- VD 생성과 surface 연결 후 다음 정책도 적용합니다.
  - `setShouldShowSystemDecors(displayId, true)`
  - `setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_LOCAL)`
- 네이티브 앱 실행은 privileged Binder의 `ActivityOptions.setLaunchDisplayId(...)`를 우선하고 필요할 때만 `am start --display ...`로 fallback합니다.
- `MirrorForegroundService`는 네이티브 VD IME를 기본 모드로, Castla IME proxy를 fallback으로 취급합니다.
- Samsung Keyboard의 분할/일반/floating 배치는 Castla가 아닌 IME 자체 상태가 결정합니다.

### 현재 진단 정책

네이티브 IME 조사 이후 진단 로그의 소음을 줄였습니다.

- 기본으로 유지하는 핵심 로그:
  - VD create / release
  - app launch path
  - IME policy application
  - build markers
  - hard errors
- 상세 진단 모드에서만 기록하는 로그:
  - repeated IME routing dumps
  - `dumpsys input_method` / `dumpsys window` snapshot parsing
  - frontend IME debug chatter
  - JMuxer per-frame / SourceBuffer verbose diagnostics
- 런타임 스위치는 `StreamSettings.verboseDiagnosticsEnabled`입니다.

## 현재 프런트엔드 구조

프런트엔드 런타임 책임은 다음처럼 분리됩니다.

- `TouchRouter`
  - 좌표 정규화
  - `MOVE` 병합 및 속도 제한
  - 제스처 통계 추적
  - control WebSocket으로 `touch` 패킷 전송
- `ViewportHost`
  - 단일 pointer listener surface
  - pointer 이벤트를 pane에 매핑
  - split layout UI와 layout flush 시점 관리
- `ViewportPane`
  - 디코더 수명 주기와 stall watchdog 관리
- `StreamRuntime`
  - control/video transport 관리
  - generation과 pane 상태 추적
  - frame reject/stall 시 디코더 복구 신호 발생

### 현재 런처 구조

런처는 더 이상 하나의 거대한 Svelte 컴포넌트로 취급하지 않습니다.

- `frontend/src/components/AppLauncher.svelte`
  - 런처 오케스트레이션, 드래그 세션, drawer scroll lock, 최종 drop 처리
- `frontend/src/components/LauncherTabs.svelte`
  - 상단 탭, 활성 탭 표시, 드래그 중 drop 안내
- `frontend/src/components/AppRow.svelte`
  - `Auto Run`, `Starred`, `Recent`의 compact card row
- `frontend/src/components/CategoryAccordion.svelte`
  - `Browse` 카테고리 accordion과 앱 목록 표시
- `frontend/src/components/DragDropOverlay.svelte`
  - left/right/bottom dropzone, 삭제 안내, drag ghost 렌더링
- `frontend/src/components/PairDialog.svelte`
  - App Pair 교환/해제 편집 UI

런처 제스처 모델은 다음 규칙을 사용합니다.

- 일반 탐색에서는 네이티브 스크롤 우선
- long-press 후 명시적 drag session 진입
- 활성 drag session만 전역 touch scroll 잠금
- 드래그 중 탭 hover는 시각 효과만 변경하고 즐겨찾기/자동실행 변경은 drop 시점에 수행
- drawer 자동 스크롤은 `pointermove` burst 대신 `requestAnimationFrame` 사용

따라서 런처 UX는 두 모드로 구분됩니다.

- **탐색 모드**: 가벼운 네이티브 스크롤과 밀도 높은 앱 탐색
- **드래그 모드**: 예측 가능한 ghost, drawer-aware 자동 스크롤, 명시적 drop target

## 현재 터치 경로

현재 경로는 다음과 같습니다.

1. 브라우저 pointer 이벤트
2. `ViewportHost.handlePointer(...)`
3. `TouchRouter.pointer(...)`
4. `StreamRuntime.control.send({ type: 'touch', ... })`
5. `ControlSocket`
6. `MirrorServer.onTouchEvent(...)`
7. `MirrorForegroundService` touch listener
8. `TouchInjector`
9. `VirtualDisplayController.injectMotionEventWithResult(...)`
10. privileged `InputManager.injectInputEvent()`

### 이미 적용된 프런트엔드 터치 변경

다음 보호책은 리팩터링 전에 이미 적용했습니다.

- `ViewportHost`의 단일 pointer listener 구조
- `TouchRouter`의 강제 `MOVE` 속도 제한
- 프런트엔드 `MOVE` 중복 제거/drop
- `TouchInjector`의 Android `MOVE` 중복 제거/throttle 안전장치
- 프런트엔드 로그를 `gesture complete`, 경고, 오류 중심으로 축소

### 현재 터치 결론

기존 `MOVE flooding` 문제는 실제로 존재했지만 더 이상 주된 장애 요인은 아닙니다.

최근 로그의 근거는 다음과 같습니다.

- `movePacketsPerSecond`는 기존 `15..22`가 아니라 보통 `2..6`
- swipe당 `gesturePackets`는 보통 `8..13`으로 안정적
- `inject_privileged durationMs`는 보통 `0.5..1.5ms`

즉 브라우저 입력 패킷 밀도 문제는 크게 완화되었습니다.

## 현재 디코더·복구 경로

프런트엔드 디코더 상태는 다음과 같이 복구 동작에 영향을 줍니다.

- `ViewportPane`은 stall watchdog을 실행하고 `runtime.recoverPaneStream(pane)`을 호출할 수 있습니다.
- `StreamRuntime.dispatchFrame(...)`은 `frameRejected`를 발생시킬 수 있습니다.
- `StreamRuntime.recoverRejectedStream(...)`은 reject가 반복되면 keyframe을 요청할 수 있습니다.

최근 적용한 완화책은 다음과 같습니다.

- frame reject 복구의 공격성 완화
- 한 번의 reject로 즉시 복구하지 않음
- 짧은 시간 안에 reject가 반복될 때만 `requestKeyframeAfterReject` 실행

불필요한 복구는 줄었지만 여전히 Android rebuild 동작과 구조적으로 결합되어 있습니다.

### 현재 스트림 generation 규칙

- rebuild/restart마다 새 stream generation 시작
- 해당 generation의 첫 decoded frame이 실제 전송될 때까지 `firstFrameReady=false` 유지
- 첫 프레임 확인 전까지 브라우저 layout을 pending 상태로 유지
- encoder rebuild 전에 SPS/PPS cache를 지우고 새 encoder 출력 또는 명시적 keyframe 요청을 통해서만 재전송

## 현재 Rebuild 경로

현재 가장 중요한 구조적 문제입니다.

Rebuild 요청은 여전히 여러 위치에서 발생할 수 있습니다.

- `MirroringPipeline.onViewportChange(...)`
- 앱 실행 준비 및 self-healing 분기
- display density listener
- codec/profile 변경 처리
- `AdaptiveBitrateManager.applyPipelineScale(...)`
- `MirrorForegroundService` 내부 fallback/recovery/self-healing 분기

하드웨어 실행은 `VirtualDisplayRebuildCoordinator`의 제한된 큐에서 직렬화하지만 요청 자체는 여러 정책 계층에서 발생합니다. 같은 pane의 동등한 요청은 enqueue 전에 짧은 시간 동안 병합합니다.

### 최근 적용한 완화책

`MirroringPipeline.rebuild(...)`는 활성 터치가 있는지 확인하고 enqueue를 최대 약 `1.5초` 지연합니다.

관련 로그:

- `[InputTrace] touch_guard ... reason=rebuild_defer ...`

이는 안전장치일 뿐 구조적 해결책은 아닙니다.

## 로그에서 확인한 장애 패턴

최근 로그에서는 다음 순서가 반복됩니다.

1. 지도/내비게이션 앱을 반복 전환
2. `frameRejected` 같은 디코더·프레임 이상 발생
3. `secondary`, 이후 `primary` rebuild 발생
4. rebuild 전후에 진행 중인 터치가 취소되거나 중단
5. rebuild 및 후속 keyframe 이후 새 `DOWN`이 결국 다음 결과로 바뀜
   - `inject_privileged ... result=false`

중요한 관찰 결과:

- 최종 실패 전에 높은 MOVE 처리량이 나타나지 않음
- 실패는 **rebuild/reconfigure 구간**과 강하게 연관됨
- `inject_false_probe`에는 올바른 앱과 focused display가 나오지만 같은 display id에 stale task/session 흔적이 남음

현재 주요 가설:

- rebuild 도중 또는 직후 display/window/task 상태 변동이 터치 입력을 무효화함
- 여러 subsystem이 복구/rebuild를 독립적으로 요청할 수 있음
- stream recovery, display rebuild, active touch injection 사이에 race가 발생함

## 최근 로그에서 얻은 결론

다음 항목은 후속 리팩터링의 확정 전제로 취급합니다.

1. 주된 문제는 더 이상 브라우저 `pointermove` 양이 아닙니다.
2. MOVE 양이 적고 주입 시간이 짧아도 `injectInputEvent()`가 실패할 수 있습니다.
3. 가장 강한 상관관계는 다음과 같습니다.
   - `frameRejected` or other recovery pressure
   - 이후 `rebuild_begin(...)` 발생
   - 이후 `injectInputEvent(...)=false` 발생
4. 현재 구조에서는 너무 많은 계층이 rebuild를 직접 요청할 수 있습니다.

## 구조적 리팩터링이 필요한 이유

현재 문제는 하나의 고립된 버그가 아닙니다. 다음 경로가 부분적으로 얽혀 있습니다.

- 입력 경로
- 디코더 복구
- 레이아웃 전환
- 앱 실행/복원
- VirtualDisplay rebuild

이미 여러 완화 패치가 있지만 근본 해결에는 더 강한 책임 분리가 필요합니다.

## 다음 리팩터링 방향

단일 rebuild/recovery 제어 계층을 목표로 합니다.

### 목표 방향

1. **입력 세션 상태**와 **display 복구 상태** 분리
2. 모든 rebuild 의도를 하나의 coordinator API로 전달
3. decoder/thermal/viewport/codec handler의 직접 rebuild 호출 금지
4. 활성 터치를 rebuild 승인 조건의 핵심 상태로 취급

### 권장 첫 리팩터링 단계

다음과 같은 단일 rebuild 요청 경계를 만듭니다.

- `requestRebuild(reason, priority, pane, width, height, options)`

다음 정책을 그 경계로 이동합니다.

- 터치 중 요청 거부 또는 지연
- 중복 요청 병합
- cooldown 적용
- launch/recovery/split 전환 중 허용할 요청 결정

### 통합해야 할 직접 `rebuild()` 호출 위치

최소한 다음 위치를 확인하고 중앙 coordinator로 전달해야 합니다.

- `MirroringPipeline.onViewportChange(...)`
- 서비스 display density listener
- codec/profile 전환 로직
- `AdaptiveBitrateManager.applyPipelineScale(...)`
- fallback/self-healing/restore 경로
- 최종적으로 rebuild를 일으킬 수 있는 모든 decoder recovery 경로

## 권장 상태 분리

### 입력 상태 머신

- `Idle`
- `TouchActive`
- `Cancelling`
- `Rejected`

### Display·복구 상태 머신

- `Idle`
- `Launching`
- `Stable`
- `Recovering`
- `Rebuilding`
- `Suspended`

다음과 같은 허용 전이를 명시적으로 정의합니다.

- `TouchActive` 중 rebuild 금지
- `TouchActive` 중 decoder recovery의 keyframe 요청은 허용
- decoder recovery의 rebuild 요청은 터치 종료 후에만 허용

## 현재 적용된 임시 안전장치

대체 구현 없이 제거해서는 안 되는 항목입니다.

- 프런트엔드 단일 pointer routing
- 프런트엔드 강제 `MOVE` 상한
- 프런트엔드와 Android의 `MOVE` 중복 제거/drop
- 터치 중 fallback 취소/생략
- `frameRejected` recovery throttling
- 터치 중 rebuild 지연

최종 구조가 아닌 전술적 보호책입니다.

## 인수인계 요약

> 긴급한 구조적 문제는 MOVE spam 자체가 아니라 decoder/layout/recovery 코드의 rebuild 압력이 활성 터치와 충돌해 대상 VirtualDisplay의 입력 경로를 손상시킬 수 있다는 점입니다.

### 2026-05-26 리팩터링 업데이트

Android에 첫 rebuild 경계를 도입했습니다.

- `MirrorForegroundService.requestRebuild(RebuildRequest)` delegates to `VirtualDisplayRebuildCoordinator` before requests enter its bounded hardware queue
- viewport, density, launch self-heal, adaptive bitrate, thermal 경로의 직접 rebuild 호출을 `MirroringPipeline.requestRebuild(...)`로 통합
- rebuild 요청에 `reason`과 `priority` 포함
- 기존 active-touch 지연 동작을 coordinator로 이동
- 같은 pane의 중복 rebuild 요청을 hardware enqueue 전 짧은 구간에서 병합

남은 후속 작업:

1. 모든 `rebuild(...)` 호출 위치 목록화
2. 계층 간 직접 복구 trigger 축소
3. decoder recovery를 명시적인 keyframe 우선/rebuild 후순위 정책으로 전환
4. 입력 및 display recovery 상태 머신 공식화

### 2026-05-26 터치 수정 결과

hard reset 이후 터치가 멈추는 문제는 해결됐습니다. 실제 실패 경계를 찾기 위해 사용한 임시 추적 코드는 런타임 경로에서 제거했습니다.

#### 확인된 근본 원인

문제는 브라우저 재연결, control transport, 앱 실행 라우팅, display 포커스 이탈이 아니었습니다.

브라우저 `pointerId`를 Android `MotionEvent.PointerProperties.id`에 그대로 전달한 것이 원인이었습니다. reload/hard reset을 반복하면 브라우저가 `36`처럼 큰 ID를 보낼 수 있지만 Android 입력 주입은 `0`, `1`, `2` 같은 작은 로컬 ID에서 안정적으로 동작했습니다.

이 불일치로 다음 현상이 발생했습니다.

- hard reset 후 실제 사용자 터치가 `injectInputEvent(...)`에서 거부됨
- 작은 로컬 ID를 쓰는 내부 보정 입력은 성공함
- 전체 reload 후 우연히 작은 pointer ID가 할당되면 다시 동작함

#### 런타임에 유지할 수정

`TouchInjector`는 활성 제스처 동안 브라우저 pointer ID를 Android 로컬 pointer ID로 재매핑합니다.

- 브라우저 ID는 외부 프로토콜 ID로 유지
- Android 주입에는 지원 범위 안의 작은 로컬 ID 사용
- `UP`, `CANCEL`, injector reset/release 시 로컬 ID 해제

이 동작 변경은 계속 유지해야 합니다.

#### 확인 후 제거한 조사 코드

다음 hard reset 조사용 임시 코드는 의도한 최종 구조에 포함하지 않습니다.

- touch packet의 hard reset generation tag
- 첫 packet hard reset 추적
- hard reset 조사에 사용한 dispatcher probe 로그
- 진단 전용 inject source tag

일반 입력과 rebuild 로그는 유지하지만 조사 전용 로그는 다시 축소했습니다.

#### 현재 터치 인수인계

> hard reset 터치 멈춤은 `TouchInjector`에서 브라우저 pointer ID를 Android 로컬 ID로 재매핑해 해결했습니다. 유지할 보호책은 MOVE 중복 제거/throttle과 터치 중 rebuild 지연입니다.

### 2026-06-01 IME 단순화 및 tapOutside 제거

`tapOutside` 기능은 의도적으로 제거했습니다.

#### 1) 원격 IME 정책
- **IME 우선 구조**: 접근성 포커스 추정 대신 IME 수명 주기 동기화를 사용합니다.
- **뷰포트 기반 dismiss 추정 제거**: 뷰포트 탭을 검색창 닫기나 텍스트 모드 종료로 추정하지 않습니다.
- **`tapOutside` control 메시지 제거**: 프런트엔드는 `{ type: "ime", op: "tapOutside" }`를 보내지 않습니다.
- **단순화된 FSM**: `IDLE`, `ANDROID_FOCUSING`, `READY`, `BLUR_PENDING`, `RECOVERING`만 유지합니다.
- **포커스 획득 전용 pointerdown**: 브라우저 user-gesture 규칙에 따라 `imeProxy` 포커스를 얻는 용도로만 사용합니다.
- **로컬 입력 격리 유지**: 브라우저 로컬 입력은 Android 키 전달과 `imeProxy` 포커스 탈취를 우회합니다.
- **세션 기반 편집 상태 동기화**: `androidFocusChanged`, `onStartInput`, `onFinishInput`, stale-session 방지를 사용합니다.

#### 2) 안정성 근거
- **지도 drag/pan 보호**: 외부 탭 dismiss 경로 자체가 없어 Google Maps 조작을 오인하지 않습니다.
- **dismiss race 제거**: `requestHideSelf()` 타이밍 race, echo 억제, cooldown/gesture 상태 관리가 사라졌습니다.
- **BACK fallback 제거**: `KEYCODE_BACK` 기반 dismiss fallback을 다시 도입하지 않습니다.

#### 3) Android 백엔드 동작
- `MirrorServer`, `ControlSocket`, `MirrorForegroundService`의 TapOutside listener 경로 제거
- 뷰포트 탭에 대한 `finishComposingText()`와 `requestHideSelf()` 호출 제거
- 원격 IME 포커스/blur는 정상 수명 주기 이벤트로만 결정

### 2026-06-01 재시작·Maps 첫 실행 안정화

미러링 재시작 시 다음 stale 상태 초기화 정책을 적용합니다.

- **재연결/재시작 후 새 실행 준비**:
  - `currentApp`에 이전 패키지가 남아 있어도 다음 실행은 새 준비가 필요한 것으로 처리
  - stale display affinity로 인해 동일 앱 첫 실행이 생략되지 않음
  - 캐시된 target package/last-launched 상태가 첫 준비를 막지 않음
- **Maps 첫 실행 보호**:
  - 미러링 재시작 후 첫 Google Maps 실행에 새 display binding, keyframe/IDR, SPS/PPS 재전송 기회, generation 초기화 제공
  - `force-stop`, `BACK`, `am start` 재시도 루프 없이 처리
- **warm start 허용**:
  - 기존 Task를 현재 VD/display로 이동 가능
  - 단 재시작 후 첫 실행은 stale 동일 앱 guard를 우회

#### 2) 동기식 Service onDestroy 정리와 Mutex crash 방지
- **동기식 정리**: `onDestroy()`의 비동기 dispatch를 `performCleanup("service_ondestroy")` 순차 실행으로 바꿔 프로세스 종료 전에 native 자원 해제를 완료합니다.
- **released flag 복구**: `executeReleaseInternal()`을 `try-finally`로 감싸 종료 후 `released`를 `false`로 되돌려 hot reload/rebuild/task restart에서 pipeline이 멈추는 문제를 방지합니다.
- **Codec/Encoder thread join**:
  - `VideoEncoder`, `JpegEncoder`에 Atomic released 경계와 `HandlerThread.join(2000)` 적용
  - binder loop/drain thread 종료 후 MediaCodec/ImageReader를 파괴해 `FORTIFY pthread_mutex_lock on destroyed mutex` crash 제거

### 2026-05-27 Hot Restart 스트림 복구 및 SSL 정리

hot restart 스트림 복구와 내장 서버 SSL 설정을 단순화했습니다.

#### 1) 재연결 시 Keyframe throttle 우회
- Android의 `setKeyframeRequester`, `onKeyframeRequest` callback에 `force: Boolean` 전달
- 새 video socket(`video_open`)에서는 `force=true`로 1000ms throttle을 우회해 즉시 keyframe 제공

#### 2) 브라우저 Frame Reject 간격 완화
- `StreamRuntime.ts`의 `consecutiveFrameRejects` 확인 구간을 500ms에서 3000ms로 확대
- 정적/저 FPS 화면에서도 reject 누적을 유지해 초기 keyframe 누락 시 3초 안에 새 keyframe 요청

#### 3) 서버 재시작 시 자동 Session Hard Reset
- `App.svelte`가 `serverInit`의 `instanceId` 변경을 추적
- Android 서비스 재시작을 감지하면 `hardReset("server_reboot")`으로 Svelte store와 decoder session을 정리하고 수동 F5 없이 런처 홈 복원

#### 4) 해상도를 보존하는 Hot Rebuild
- 초기 layout 크기가 비어 720x720 VD를 만들던 문제 수정
- `primary.requestedWidth/Height`를 우선해 비율 불일치와 화면 늘어짐 방지

#### 5) SSL/HTTPS 및 Keystore 단순화
- 외부 동적 인증서 다운로드와 `castla.p12` 로딩 제거
- NanoHTTPD를 9090 포트의 경량 Plain HTTP/WebSocket 서버로 단순화해 지연과 인증서 만료 crash 제거


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

## 2026-08-02 VirtualDisplay Task 라우팅 및 인코더 수명 주기 업데이트

### 현재 앱 실행 정책

`MirrorForegroundService.MirroringPipeline.launchComponent()`는 패키지 전체의 Task 존재 여부가 아니라 대상 VirtualDisplay를 기준으로 앱을 라우팅합니다.

1. 대상 VD에 일치하는 Task가 없으면 해당 display에 새 Task 실행
2. 대상 VD에 일치하는 Task가 있으면 새 Activity 없이 해당 Task를 앞으로 이동
3. 다른 display에만 Task가 있으면 대상 VD를 실행 위치로 유지하고 필요할 때 새 Task 생성
4. 강제 cold start는 기존 force-stop/new-task 경로 사용

Task 결정은 `LaunchPlanner`에서 중앙화합니다. privileged Binder의 native `moveTaskToFront`를 우선하고 구형 Android/One UI에서는 shell fallback을 유지합니다.

### Display session과 인코더 정책

`DisplayLaunchSession`은 launch 준비와 Task 라우팅을 분리합니다. `DisplaySizePolicy`는 실제 VD/인코더 크기의 단일 기준입니다.

- pipeline 최대 높이 제한 적용
- 높이 제한 시 화면 비율 유지
- 가로·세로를 16픽셀 인코더 경계에 정렬
- 최소 320픽셀 하드웨어 경계 보장

크기가 바뀌면 encoder surface를 rebuild하고 새 stream generation을 시작합니다. 수명 주기는 `release -> create -> VD에 surface 연결 -> stream generation 시작 -> encoder 시작 -> keyframe 요청` 순서입니다. 같은 크기의 Task를 재사용할 때는 encoder를 rebuild하지 않습니다.

### 호환성과 검증

Android/One UI 버전별 signature 차이를 수용하기 위해 Task 조회와 privileged ActivityTaskManager 호출은 reflection 기반을 유지합니다. One UI 9에서는 새 Task 실행, 같은 VD Task 재사용, 여러 앱 전환, 해상도 변경, encoder 재연결을 검증했습니다. One UI 8.5 실기기 회귀 검증은 남아 있습니다.

## 2026-08-04 분리된 VirtualDevice Power Group

### Android 13 이상

Shizuku 서비스는 shell의 `APP_STREAMING` companion association으로 `VirtualDevice`를 생성하고, 그 장치에서 encoder surface를 받는 `VirtualDisplay`를 생성합니다. Display는 `TRUSTED`, `PUBLIC`, `OWN_CONTENT_ONLY` 조건을 사용하며, Android DisplayManagerService가 `DEVICE_DISPLAY_GROUP`을 부여합니다. 따라서 물리 Display 0의 power group과 Castla VD의 power group이 실제로 분리됩니다.

물리 전원 버튼으로 group 0이 sleep에 들어가도 VD group은 awake와 `Display.STATE_ON`을 유지합니다. 이 경로에서는 `KEYCODE_WAKEUP`, physical-display wake pulse, blackout activity, server video freeze, delayed resume 및 VD rebuild를 사용하지 않습니다. `SCREEN_OFF`/`SCREEN_ON` 브로드캐스트는 단순 물리 상태 추적과 연결 종료 유예에만 사용합니다.

웹 진단의 `physicalScreenOff`는 상태 표시 및 연결 유예용입니다. WebCodecs 렌더링을 멈추는 기존 `screenOff` 값은 legacy recovery가 활성화된 경우에만 true이므로 Android 13+에서는 VD가 생산하는 프레임을 계속 그립니다.

VirtualDevice 생성에 실패하면 동일 power group의 legacy VD로 조용히 fallback하지 않고 생성 실패로 처리합니다.

### Android 8–12L

Android 26–32는 VirtualDevice API를 사용할 수 없으므로 기존 DisplayManagerGlobal 기반 VD와 screen-off 복구 상태 머신을 유지합니다. freeze/resume, VD keep-alive, blackout 및 revive/rebuild 판단도 이 legacy 경로에서만 실행됩니다.

### 실기기 검증

Samsung SDK 37에서 Castla display 135가 group 7에 배치되고, 물리 group 0이 asleep인 동안 display 135는 ON을 유지했습니다. 물리 전원 버튼으로 화면을 끈 뒤에도 encoder frame counter가 10000에서 12000까지 증가했고 웹 미러링도 계속 표시됐습니다. 이 과정에서 wake key, blackout 및 freeze/revive는 실행되지 않았습니다.

### 2026-08-04 Coordinator 및 결정적 빌드 업데이트

- browser lifecycle, remote input, display 진단, VD rebuild scheduling, TLS, stream metadata, HTTP content에 명시적 runtime 경계 적용
- VD rebuild queue를 16개 pending request로 제한하고 `Channel.UNLIMITED` 대신 coroutine backpressure 적용
- 동등한 rebuild 요청은 순수 함수 `RebuildRequestPolicy`로 판단
- native VD IME를 우선하고 proxy 처리는 `RemoteInputPolicy`로 명시적 제한
- `CASTLA_BUILD_TIMESTAMP`를 지정하지 않으면 `frontend/`를 마지막으로 변경한 commit SHA를 사용해 동일 소스의 asset hash를 결정적으로 생성
- stream generation/metadata replay, rebuild queue/병합 정책, native IME proxy gating, browser layout, disconnect 정책 회귀 테스트 추가

## 2026-08-10 알림 기록 및 세션 종료 업데이트

### 알림 수집과 전달

`CastlaNotificationListenerService`는 미러링 서비스가 사용 가능한 동안 Android 알림을 수신합니다. ongoing 알림, group summary, 텍스트와 이미지가 모두 없는 payload는 버립니다. MessagingStyle 알림은 최신 메시지 텍스트를 우선하며 대화방명과 발신자를 별도로 추출합니다. 이미지 자체는 전송하지 않고 `hasImage` metadata만 보냅니다.

허용된 payload는 MirrorServer control socket을 통해 `notification` 메시지로 broadcast합니다. 사용자가 선택한 package allowlist는 Android가 아니라 프런트엔드가 소유합니다. 프런트엔드는 기존 앱 선택값을 적용한 뒤 history에 추가하거나 실시간 overlay를 표시합니다. listener 연결, 필터링, 전달, control client 수, 프런트엔드 허용 판단은 알림 진단에서 확인할 수 있습니다. 미러링 서비스 시작 시 알림 접근 권한이 이미 있으면 APK 교체나 listener process 단절에서 복구하도록 rebind를 요청합니다.

### 프런트엔드 알림 기록

프런트엔드는 허용된 알림을 현재 세션 메모리에 최대 100개 유지합니다. 최신순으로 정렬한 뒤 다음 두 단계로 묶습니다.

1. 앱 package
2. 대화방 제목

개인 대화에서 발신자와 대화 제목이 같으면 중복 발신자 표시를 숨깁니다. 단체 대화는 메시지별 발신자와 시간을 유지합니다. 이미지 전용 및 이미지+텍스트 알림은 현지화된 대체 문구를 사용합니다. 앱 헤더와 대화방 헤더는 각각 접기/펴기를 지원하며 history 상태와 관계없이 새 알림의 실시간 overlay는 계속 표시됩니다.

floating history control은 5초 후 흐려지고 8초 후 숨겨져 미러링 화면을 계속 가리거나 터치를 가로채지 않습니다. 새 알림이 오면 다시 나타나며 숨겨진 동안에도 런처 설정에서 history를 열 수 있습니다. 프런트엔드 hard reset 시 메모리 history를 비웁니다.

ControlSocket 연결성은 WebSocket `onopen`으로 판단하고 `serverInit`은 제어 protocol 준비 완료로 별도 처리합니다. 최초 접속 결과가 정해지기 전에는 실패 overlay를 표시하지 않으며, 초기 실패는 3초, 기존 연결 단절은 600ms 유예 후 안내합니다. 그 전에 연결이 복구되면 화면을 가리지 않습니다.

### VirtualDisplay Task 종료 처리

브라우저 연결 종료 시 Task 정리가 끝날 때까지 각 display token과 privileged Binder를 유지합니다. privileged service는 대상 VirtualDisplay의 Task ID를 조회해 해당 Task만 제거한 뒤 Home을 열고 display를 release합니다. display 단위로 정리하며 package 전체를 force-stop하지 않으므로 물리 display에서 실행 중인 같은 앱은 종료되지 않습니다.

## 2026-08-10 UID 단위 오디오 스트리밍 및 A/V 동기화 업데이트

- `audio_enabled=false`에서는 Android의 기존 오디오 출력을 유지하고, Tesla Bluetooth 비디오 앱에만 별도 화면 지연을 적용합니다.
- `audio_enabled=true`에서는 Shizuku shell의 UID-scoped AudioPolicy loopback으로 48kHz stereo PCM을 캡처해 Opus 또는 PCM으로 브라우저에 전송합니다.
- 기본 앱 정책은 일반 앱 browser-only, 내비게이션 phone-direct입니다. Samsung Separate App Sound 값을 읽을 수 있으면 우선하고, 그렇지 않으면 Castla 내비게이션 분류로 fallback합니다.
- `AudioTargetRegistry`가 실행 앱을 package/user 단위로 유지해 YouTube → TMAP → YouTube 전환에서도 YouTube가 계속 브라우저로 출력됩니다.
- 실효 캡처 키가 같으면 AudioPolicy를 재시작하지 않아 전환 순간의 폰 출력 누출과 팝음을 제거합니다.
- Opus-first/PCM-first 설정, encoder/browser capability 협상, Opus 무출력·decode 오류의 PCM fallback을 지원합니다.
- Bluetooth 지연(0–1000ms)과 스트리밍 A/V 오프셋(-1000–1000ms)을 별도로 저장합니다. 스트리밍 기본값은 `-30ms`이며 음수는 비디오, 양수는 오디오 지연입니다.
- 스트리밍 지연 변경은 control WebSocket과 Web Audio `DelayNode`를 사용해 오디오 스트림을 재시작하지 않고 반영합니다.

자세한 내용은 [audio-streaming-architecture.md](audio-streaming-architecture.md)를 참조하십시오.
