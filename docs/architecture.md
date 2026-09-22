# Castla 아키텍처

이 문서는 현재 운영 코드의 책임 경계와 데이터 흐름을 설명합니다. 변경 이력이나 과거 설계안은 다루지 않습니다.

## 시스템 개요

Castla는 휴대폰의 물리 화면을 그대로 복제하지 않습니다. Shizuku 권한으로 독립 가상 디스플레이를 만들고, 그 안에서 실행한 Android 앱을 Tesla 브라우저로 전송하는 원격 앱 환경입니다.

```text
Android 앱
  MainActivity
      └─ MirrorForegroundService
           ├─ Shizuku PrivilegedService
           ├─ primary / secondary MirroringPipeline
           ├─ MirrorServer (HTTP(S) + WebSocket, 9090)
           ├─ 오디오 캡처 및 라우팅
           └─ 알림 전달

Tesla 브라우저
  Svelte UI
      ├─ ControlTransport
      ├─ primary / secondary VideoTransport
      ├─ AudioPlayer
      ├─ WebCodecs 또는 JMuxer 디코더
      └─ 레이아웃·터치·알림 UI
```

## Android 런타임

### `MainActivity`

설정과 권한 상태를 표시하고 미러링 서비스의 시작·종료를 요청합니다. 일반 앱 아이콘 실행은 화면만 열며 서버를 자동으로 시작하지 않습니다.

설치 후 등록되는 동적 앱 바로가기는 같은 `MainActivity`로 다음 명시적 action을 전달합니다.

- `com.castla.mirror.action.START_SERVER`: 설정과 Shizuku 준비가 끝난 뒤 서버 시작
- `com.castla.mirror.action.STOP_SERVER`: 정상 종료를 요청하고 Castla 태스크 닫기

삼성 모드 및 루틴은 이 두 앱 동작을 사용할 수 있습니다. Castla 태스크가 제거된 경우에도 실행 중인 서비스는 `onTaskRemoved()`에서 정상 종료를 요청합니다.

### `MirrorForegroundService`

서비스가 런타임 자원의 최종 소유자입니다.

- 내장 서버와 브라우저 세션
- primary·secondary 파이프라인
- Shizuku 연결과 재연결 감시
- 오디오 캡처와 출력 라우팅
- WakeLock, 열 제한, 적응형 비트레이트
- 물리 화면 꺼짐 처리
- 종료 시 소켓, 인코더, 가상 디스플레이와 Task 정리

서비스 종료는 비동기로 시작되지만 정리 경로는 하나로 수렴합니다. 알림의 종료 동작, 앱의 종료 버튼, 루틴의 서버 종료 및 태스크 제거가 같은 정리 흐름을 사용합니다.

### `MirroringPipeline`

primary와 secondary는 각각 하나의 `MirroringPipeline` 인스턴스로 운영됩니다. 파이프라인은 다음 상태와 자원을 캡슐화합니다.

- 가상 디스플레이와 현재 display ID
- H.264 또는 MJPEG 인코더
- 요청 크기, 실제 스트림 크기와 generation
- 현재 앱·브라우저 콘텐츠
- 터치 주입기와 최근 상호작용 상태
- Task 실행, 재사용 및 복구 상태

두 파이프라인을 함께 조정하는 정책은 서비스에 두고, 한 파이프라인이 다른 파이프라인의 자원을 직접 변경하지 않도록 유지합니다.

## 가상 디스플레이와 앱 실행

Shizuku 사용자 서비스인 `PrivilegedService`가 숨겨진 Android API와 Binder 호출을 담당합니다. 앱 프로세스는 이 경계를 통해 가상 디스플레이 생성, Task 조회·이동·제거, 입력 주입과 IME 정책을 요청합니다.

Android 13 이상에서는 가능한 경우 `VirtualDeviceManager`의 `APP_STREAMING` VirtualDevice에 디스플레이를 소속시켜 물리 화면과 전원 그룹을 분리합니다. Android 8–12L은 기존 가상 디스플레이와 화면 꺼짐 복구 정책을 사용합니다. 최신 경로 생성에 실패했을 때 조용히 물리 화면 그룹의 디스플레이로 대체하지 않습니다.

앱 실행은 요청한 display를 기준으로 결정합니다.

1. `DisplayLaunchSession`이 가상 디스플레이와 인코더 상태를 준비합니다.
2. `LaunchPlanner`와 `TaskRoutingCoordinator`가 대상 display의 기존 Task를 조회합니다.
3. 대상 display에 Task가 있으면 앞으로 가져오고, 없으면 그 display에 새 Task를 만듭니다.
4. 해상도가 같고 인코더가 정상이면 Task 전환만 수행합니다.
5. 크기가 달라졌거나 인코더가 없을 때만 새 stream generation을 시작합니다.

해상도 계산은 `DisplaySizePolicy`에서 단일화하며 가상 디스플레이와 인코더가 같은 결과를 사용합니다.

## 영상 스트리밍

`MirrorServer`는 9090 포트에서 내장 프론트엔드와 API를 제공하고 다음 WebSocket을 엽니다.

- `/ws/control`: 세션 초기화, 앱 실행, 레이아웃, 터치, 진단과 설정
- `/ws/video?channel=primary`: primary 영상
- `/ws/video?channel=secondary`: secondary 영상
- `/ws/audio`: 오디오 설정 및 데이터

영상 세션은 generation으로 구분합니다. 인코더나 해상도가 바뀌면 새 generation이 시작되고, 브라우저는 그 generation의 첫 프레임이 준비된 뒤에만 화면을 확정합니다. 이전 generation에서 늦게 도착한 프레임은 폐기합니다.

H.264는 WebCodecs를 우선 사용하고 지원 여부나 런타임 상태에 따라 JMuxer/MSE 경로를 사용할 수 있습니다. MJPEG는 호환용 코덱 경로입니다. 재연결 시에는 가상 디스플레이를 즉시 파괴하지 않고 SPS/PPS 재전송과 키프레임 요청을 먼저 사용합니다.

동시에 제어할 수 있는 브라우저 세션은 하나입니다. 새 제어 연결은 기존 세션 상태에 따라 인계받거나 `controlBusy`로 거부됩니다.

## 브라우저 프론트엔드

프론트엔드는 Svelte 5와 TypeScript로 구성됩니다.

- `components/`: 런처, 뷰포트, 팝업, 알림과 설정 UI
- `runtime/`: 스트림 generation과 상태 감시
- `transport/`: 제어·영상·오디오 통신
- `decoder/`: WebCodecs, JMuxer와 디코딩 지연 처리
- `compositor/`, `viewport/`: 화면 배치와 크기 계산
- `touch/`: 브라우저 좌표를 대상 가상 디스플레이 좌표로 변환
- `ime/`: 원격 텍스트 입력 fallback
- `lib/`: 레이아웃·실행 재사용·알림 등의 순수 정책

레이아웃은 primary 하나와 선택적인 secondary 하나를 기준으로 합니다. secondary는 left, right, top, bottom 또는 popup에 배치할 수 있습니다. 브라우저의 시각 크기, 서버에 요청한 viewport 크기 및 터치 좌표 변환은 같은 배치 상태를 사용해야 합니다.

자세한 사용자 조작은 [프론트엔드 사용 가이드](frontend-user-guide.md)를 참조하십시오.

## 입력과 IME

포인터 입력은 브라우저의 `TouchRouter`가 pane과 좌표를 결정하고 Android의 `TouchInjector`가 대상 display에 주입합니다. 레이아웃 전환 중에는 이전 화면 좌표가 새 display에 전달되지 않도록 입력을 차단합니다.

텍스트 입력의 우선 경로는 trusted 가상 디스플레이 안에서 실행되는 Samsung Keyboard 또는 Gboard와 대상 앱의 일반 `InputConnection`입니다. `CastlaImeService`, `RemoteImeBridge`와 프론트엔드 `ImeBridge`는 호환용 fallback으로 유지됩니다.

## 오디오와 알림

오디오 스트리밍이 켜지면 앱 UID를 제한한 `REMOTE_SUBMIX` AudioPolicy로 PCM을 캡처하고 Opus 또는 PCM으로 전송합니다. 내비게이션 앱은 설정에 따라 휴대폰 출력으로 분리할 수 있습니다. 세부 규칙과 wire format은 [오디오 아키텍처](audio-streaming-architecture.md)를 참조하십시오.

알림 접근 권한이 있으면 `CastlaNotificationListenerService`가 허용 가능한 알림 데이터를 제어 소켓으로 전달합니다. 표시할 앱 목록과 브라우저 내 기록은 프론트엔드가 관리합니다.

## 화면 꺼짐과 복구

- Android 13 이상: 분리된 VirtualDevice 전원 그룹을 우선 사용하며 물리 화면 OFF를 영상 정지 신호로 취급하지 않습니다.
- Android 8–12L: legacy 화면 꺼짐 상태 머신이 필요할 때 가상 디스플레이와 스트림을 복구합니다.
- 소켓 재연결: 기존 display와 앱 Task를 보존하고 키프레임·인코더 복구를 우선합니다.
- 종료: 대상 가상 디스플레이의 Task만 제거하며 패키지 전체 `force-stop`은 사용하지 않습니다.

복구 코드는 사용자의 명시적 앱 실행을 임의로 반복하지 않아야 합니다. 자동 재실행보다 display, surface, encoder와 stream generation 복구를 우선합니다.

## 빌드 구조

Android 빌드 전에 Gradle이 다음 작업을 연결합니다.

```text
pnpmInstallFrontend
  -> buildFrontend
  -> copyFrontendDistToAssets
  -> Android preBuild
```

프론트엔드 의존성 manifest, 소스·설정, 유효 build ID와 출력 디렉터리가 Gradle 입력·출력으로 등록되어 있습니다. 변경이 없으면 세 작업 모두 `UP-TO-DATE`로 생략됩니다. 기본 build ID는 `frontend/`를 마지막으로 변경한 Git commit이며 릴리스 자동화는 `CASTLA_BUILD_TIMESTAMP`로 덮어쓸 수 있습니다.

주요 검증 명령은 다음과 같습니다.

```bash
./gradlew :app:testDebugUnitTest
cd frontend && pnpm test && pnpm run check
```

## 유지보수 원칙

- 현재 코드와 대응하지 않는 목표 설계나 완료 이력을 아키텍처 문서에 누적하지 않습니다.
- 상태 판단 로직은 가능한 한 순수 policy 클래스로 분리하고 단위 테스트를 추가합니다.
- display 준비, Task 라우팅 및 encoder lifecycle을 하나의 조건문에 다시 결합하지 않습니다.
- 프론트엔드 레이아웃 상태와 Android의 실제 stream generation을 구분합니다.
- 프로토콜 변경 시 Kotlin 송신부, `frontend/src/protocol.ts`와 관련 테스트를 함께 수정합니다.
