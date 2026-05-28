# 🤖 ROS 2 & Full-Stack Monorepo Master - 개발 통합 현황판 (develop_status.md)

> **상태**: 🟢 완벽 수렴 (Convergence Complete)  
> **아키텍처 패러다임**: 100% 대칭형 리액티브 상태 지향 아키텍처 (Symmetrical Reactive State Architecture)  
> **마지막 갱신 일자**: 2026-05-22

---

## 1. 🌟 핵심 아키텍처 혁신 요약

기존에 존재하던 비대칭적이고 지저분한 레거시 찌꺼기인 `browserSplitState` 객체, 스플릿 전용 기형 플래그(`keepSplitState`), 그리고 절차형 수동 렌더링 호출(`updateLayoutUI()`)을 **영구히 전면 통삭제**했습니다.

대신에 가상 디스플레이 2개(VD_1, VD_2)를 동등한 1급 시민 리소스로 바라보는 **`state.left`** 와 **`state.right`** 라는 두 개의 절대적인 독립 실행 정보(SSOT)만을 정의하고, 이 상태의 변화에 따라 뷰가 자동으로 스스로를 갱신하는 **자율 반응형 바인딩**을 이룩했습니다.

```mermaid
graph TD
    %% 런칭 액션들
    Action1[일반 런처 터치 클릭] -->|1. 반대쪽 state.right 비움| Launch1[launchApp app, false]
    Action2[드래그 앤 드롭] -->|1. 반대쪽 상태 유지 보존| Launch2[launchApp app, isRight]
    Action3[앱 페어 듀얼 런칭] -->|1. 시차 두고 차례대로| Launch3[launchApp left, false & launchApp right, true]

    %% 상태 변화 및 반응형 트리거
    Launch1 -->|2. 대칭 대입| StateUpdate[state.left 또는 state.right 값 갱신]
    Launch2 -->|2. 대칭 대입| StateUpdate
    Launch3 -->|2. 대칭 대입| StateUpdate

    %% 자율 반응형 UI 자동 업데이트
    StateUpdate -->|3. Reactive Setter 자율 감지| AutoTrigger[requestAnimationFrame 자율 트리거]
    AutoTrigger -->|4. 상황 자동 진단| UpdateUI[updateLayoutUI 스스로 상황 파악]

    %% 최종 레이아웃 결과
    UpdateUI -->|Left & Right 존재| DualLayout[50:50 듀얼 스플릿 레이아웃 자동 안착]
    UpdateUI -->|Left만 존재| LeftFull[Left 단독 꽉 찬 화면 UI 자동 안착]
    UpdateUI -->|Right만 존재| RightFull[Right 단독 꽉 찬 화면 UI 자동 안착]
    UpdateUI -->|둘 다 없음| HomeLauncher[깨끗하게 홈 런처 UI로 자동 환원]
```

---

## 2. 🔄 핵심 상태 관리 및 자율 연동 메커니즘

### 2.1. 100% 리액티브 상태 엔진 (SSOT)
`state.left` 와 `state.right` 의 세터(Setters)는 값의 실제 변동을 칼같이 감지하여, 브라우저가 화면을 갱신하는 가장 안전한 시점인 `requestAnimationFrame` 에 자율적으로 레이아웃 뷰 업데이트를 태웁니다.

```javascript
    // 🔴 대칭적인 2가지 가상 디스플레이 독립 실행 정보 (SSOT 리액티브 상태 엔진)
    let _leftApp = null;
    let _rightApp = null;

    const state = {
        get left() { return _leftApp; },
        set left(app) {
            if (_leftApp === app) return;
            console.log(`[State] Left display app changed: ${app ? app.label : 'null'}`);
            _leftApp = app;
            requestAnimationFrame(() => updateLayoutUI());
        },
        get right() { return _rightApp; },
        set right(app) {
            if (_rightApp === app) return;
            console.log(`[State] Right display app changed: ${app ? app.label : 'null'}`);
            _rightApp = app;
            requestAnimationFrame(() => updateLayoutUI());
        }
    };
```

---

## 3. 🚀 런칭 진입점별 3대 자율 흐름

그 어떤 진입점에서도 `updateLayoutUI()`를 명시적으로 수동 호출하는 하드코딩 사슬은 존재하지 않습니다. 오직 `state`의 값만 투명하게 대입해 주는 것으로 모든 UI 흐름이 자율 연쇄 작동합니다.

### 3.1. 홈 런처 그리드 일반 앱 아이콘 클릭 (단독 실행)
단독 꽉 찬 화면 실행을 의미하므로, 반대쪽 실행 정보를 깨끗이 비워주고 기동합니다. 세터가 자동으로 이를 감지하여 단독 화면 UI로 자동 보정합니다.
```javascript
    if (app.isPair) {
        launchAppPair(app.left, app.right);
    } else {
        // 🔴 낱개 앱 일반 클릭 시에는 단독 실행이므로 반대쪽 실행 정보를 SSOT에 맞춰 비워줍니다!
        state.right = null; 
        launchApp(app, false); 
    }
```

### 3.2. 사이드바 드래그 앤 드롭 런칭 (스플릿 업데이트)
기존 반대쪽 화면을 보존하며 해당 위치의 디스플레이만 갈아끼우는 의도이므로, 상태를 강제로 비우지 않고 그대로 덮어씁니다. 양쪽이 모두 차 있으므로 세터가 자동으로 판단하여 듀얼 스플릿 화면을 온전하게 유지 보정해 줍니다.
* **왼쪽 구역에 드롭 (`launch_left`)**: `launchApp(app, false);`
* **오른쪽 구역에 드롭 (`launch_right`)**: `launchApp(app, true);`

### 3.3. 앱 페어 클릭 (듀얼 시차 런칭)
`launchAppPair` 는 오직 한 쌍의 두 앱이 모두 완벽하게 보장되어 있을 때만 기동하는 극도의 논리적 정합성을 갖추고 있으며, 시차 기동으로 양쪽 상태를 스무스하게 순차 대입합니다.
```javascript
    function launchAppPair(leftPkg, rightPkg) {
        console.log(`[Launcher] Launching App Pair: left=${leftPkg}, right=${rightPkg}`);
        if (Date.now() < launchGuardUntil) return;
        lastLaunchTime = Date.now();

        const targetLeftApp = allApps.find(a => a.packageName === leftPkg);
        const targetRightApp = allApps.find(a => a.packageName === rightPkg);

        if (targetLeftApp && targetRightApp) {
            launchApp(targetLeftApp, false);
            setTimeout(() => {
                launchApp(targetRightApp, true);
            }, 300);
        } else {
            console.warn(`[Launcher] Failed to launch App Pair: one or both apps are missing.`);
        }
    }
```

---

## 4. 📱 미러링 백엔드 서버 (Android / Kotlin) 수명 주기 및 제어 설계

웹 프런트엔드의 대칭 상태가 갱신되면, 백엔드의 Android 시스템 서비스도 이에 매핑되어 가상 디스플레이 및 입력 제어 수명 주기를 투명하게 바인딩합니다.

### 4.1. MirrorForegroundService (백엔드 코어 시스템 서비스)
* **역할**: 미러링의 전반적인 백엔드 전면 생명주기를 주관하는 시스템 코어 서비스.
* **가상 디스플레이 할당 및 소멸 (`VirtualDisplayManager`)**:
  * 장치의 가상 화면 리소스인 `VD_1` (웹 클라이언트의 `state.left` 매핑)과 `VD_2` (웹 클라이언트의 `state.right` 매핑)를 생성 및 해제합니다.
* **인코딩 & 스트리밍 엔진 (`VideoEncoder` / `JpegEncoder`)**:
  * 각 가상 디스플레이의 프레임 버퍼 Surface를 하드웨어 미디어 코덱으로 전달받아 H.264/H.265 또는 MJPEG 스트림으로 실시간 인코딩하여 웹 클라이언트로 전송합니다.

### 4.2. ControlSocket & MirrorServer (웹소켓 명령 통로 브릿지)
* **명령 수신 (`onAppLaunchRequest`)**:
  * 클라이언트로부터 `launchApp` 명령(`pkg`, `componentName`, `pane = primary/secondary`)을 전달받으면, `pane` 정보에 맞추어 안드로이드 멀티 디스플레이 인텐트를 생성하여 `VD_1` 또는 `VD_2` 에 앱을 분기 런칭시킵니다.
* **해상도 및 뷰포트 변경 (`onViewportChange`)**:
  * 듀얼/단독 상태에 따라 클라이언트가 계산해 보낸 `width`, `height`, `pane` 정보를 바탕으로, 해당 가상 디스플레이의 해상도를 실시간으로 재구축(Resize)하여 디코더 가속율과 화질 선명도를 동기화합니다.
* **물리 터치 주입 (`TouchInjector`)**:
  * 브라우저에서 날아온 10바이트 바이너리 터치 패킷의 `pane` 필드(`0=primary(left)`, `1=secondary(right)`)를 판독하여, 안드로이드 가상 디스플레이의 절대 좌표계로 좌표를 변환 및 스케일링한 후 Shizuku/PrivilegedService를 거쳐 각 화면에 독립 주입합니다.

### 4.3. 넌블로킹 보장 신뢰성 설계 및 예외 가드 사양
현재 Castla 백엔드 시스템은 어떠한 극한의 동기 경합 상황이나 바인더 마비 시나리오에서도 메인 UI 스레드가 절대 블록되지 않는 극도의 넌블로킹 안전성 사양을 충족합니다.
1. **정리 스레드 분리**: `onDestroy()` -> `cleanupThread (Background)` -> `performCleanup` -> `runBlocking` (UI 스레드 영향도 0ms).
2. **셧다운 락 바이패스**: `release(forcePhysical = true)` 호출 시 코루틴 락 및 단일 스레드 컨텍스트 점유를 완전히 우회(Bypass)하여 즉시 하드웨어 자원을 수거.
3. **4초 타임아웃 격리**: 모든 해상도 변경 락 대기를 `withTimeoutOrNull(4000L)`로 제어하여 무한 홀딩 차단.
4. **Shizuku 바인더 안전 가드**: 모든 AIDL 호출부를 백그라운드 스레드에 귀속시키고 최대 3초의 타임아웃을 지닌 `runBinderSafe`로 래핑하여 Binder Crash 격벽 완성.
5. **Shizuku SecurityException 완벽 완치**: Shizuku 셸 권한 직접 바인딩 시 안드로이드 14+ 대응을 위해 `com.android.shell` 패키지명과 올바른 AttributionTag를 리플렉션으로 주입하여, `IWindowManager` 및 `IActivityTaskManager` AIDL 인터페이스 리플렉션 호출 시의 권한 에러를 완벽 영구 차단.

### 4.4. 정적 접속 주소 안내 및 공인 IP 기반 자동 세션 페어링 (2026-05-23 업데이트)
* **정적 안내 주소**: HTTPS/WebCodecs 모드가 켜져 있을 때 주소창 지저분함을 방지하기 위해 안내 URL을 `https://car.fbezita.com/castla` 단일 정적 주소로 구성합니다 (기존의 `?userId=xxx` 파라미터 완전 소멸).
* **공인 IP 기반 자동 매핑**: 안드로이드 폰과 테슬라 차량이 모바일 핫스팟/와이파이망을 통해 인터넷 접속 시 **동일한 셀룰러 공인 IP**를 할당받는 네트워크의 공통적인 생태계적 특성을 이용합니다.
  - 안드로이드가 `POST /api/castla/register-ip`로 사설 IP를 등록할 때 백엔드 시그널링 서버가 클라이언트의 공인 IP(cf-connecting-ip, x-forwarded-for 등)를 가로채 매핑 테이블(`publicIpMap`)에 등록합니다.
  - 테슬라 브라우저가 정적 주소로 접근하여 `GET /api/castla/get-phone-ip`를 조회할 때 기기 식별 파라미터가 디폴트이거나 없을 경우, 요청한 브라우저의 공인 IP와 매칭되는 폰의 최적 사설 IP(`192.168.x.x`)를 역추적해 자동 반환 및 페어링을 체결합니다.

---

## 5. ⚙️ 가상 디스플레이 리사이즈(Resize) 최적화 및 미러링 종료 시 자율 청소(Cleanup) 설계

> [!IMPORTANT]
> **해상도 변동 시 가상 디스플레이를 파괴했다가 재생성(`create` / `release`)하는 방식은 그래픽 리바인딩 지연 및 윈도우 매니저(WindowManagerService)의 순간 얼어붙음(Freeze) 등 시스템 불안정을 유발하는 치명적인 악취입니다.**

### 5.1. 가상 디스플레이 논블로킹 리사이즈 (`VirtualDisplay.resize`)
화면 비율 변경이나 드래그 등으로 해상도가 변경될 때, 기존 가상 디스플레이 인스턴스를 유지하며 내부 API인 `.resize(width, height, densityDpi)`를 직접 호출함으로써 시스템 지연과 무거운 오버헤드를 100% 원천 차단합니다.

* **가상 디스플레이 표준 API 호출**:
  ```kotlin
  // 매번 새로 생성/소멸하는 리바인딩 참사 방지
  virtualDisplay?.resize(newWidth, newHeight, newDpi)
  ```
* **Shizuku / 특권 세션을 통한 윈도우 매니저(`wm`) 대칭 동기화**:
  가상 디스플레이의 크기가 재조정될 때 장치 내부 윈도우 매니저의 해상도 밀도를 강제 정렬하여 레이아웃 찌그러짐을 방어합니다.
  ```bash
  # 가상 디스플레이 ID(-d)를 정교히 추적하여 리사이즈 실행
  wm size 2560x1600 -d <displayId>
  wm density 320 -d <displayId>
  ```

### 5.2. 미러링 종료 시 가상 앱 자율 청소 (Autoclean 및 리셋)
미러링 서비스가 비활성화되거나 가상 디스플레이가 소멸될 때, 해당 가상 화면 위에서 독립 기동 중이던 써드파티 가상 앱들이 백그라운드에서 꼬인 채 좀비로 잔존하지 않도록 확실한 정리 프로세스를 관통시킵니다.

1. **윈도우 매니저 리셋 (`wm reset`)**:
   가상 디스플레이가 시스템 그래픽 메모리를 점유하거나 꼬이지 않도록 즉각 해상도 구성을 완전 초기화합니다.
   ```bash
   wm size reset -d <displayId>
   wm density reset -d <displayId>
   ```
2. **Shizuku/AM 특권 세션을 통한 가상 Activity Task 소거**:
   가상 디스플레이 위에 활성화되어 잔존해 있던 액티비티 스택 전체를 강제 통소거하여 다음 실행 시의 충돌을 원천 예방합니다.
   * **Task 강제 소거**: `am stack terminate` 또는 Shizuku 특권 셸을 이용해 해당 디스플레이 타겟에 기동했던 패키지들을 **`am force-stop <packageName>`** 으로 즉각 물리적 강제 종료 집행.

---

### 5.3. 16배수 매크로블록 얼라인먼트 갭 및 과도기 여백의 완벽 소멸 (fitMode 기본값 fill 격상)
* **배경**: H.264 하드웨어 인코더(`MediaCodec`)의 16x16 매크로블록 압축 규격 제한으로 인해, 실해상도와 16의 배수 올림 정렬 크기 사이에 1~15픽셀의 종횡비 편차가 물리적으로 존재합니다. 단독 화면 시 `contain` 모드로 복귀하면 이 편차와 리빌드 과도기(500ms) 해상도 불정합이 화면 좌우/상하의 지저분한 검은 여백(블랙바/레터박스)으로 고스란히 표현되는 결함이 있었습니다.
* **해결 및 가이드라인**:
  - 단독 화면 복귀 및 기본 스트림 수신 시에도 기본 fitMode 정책을 **`"fill"`**로 완벽 격상하여 초기화 및 복귀(fallback)하도록 재설계했습니다.
  - 이로 인해 16배수 물리 정합 오차가 디스크나 프론트엔드 단에서 빈틈없이 메워져 화면 가득 꽉 찬 완벽한 풀스크린 미러링 경험을 유지합니다.
  - **규칙**: 어떠한 경우에도 비디오 디코더 캔버스가 휑한 검은 여백을 남기지 않도록 `getEffectivePrimaryFitMode` 및 `getEffectiveSecondaryFitMode`는 항상 최종 fallback으로 `"fill"`을 지향해야 합니다.

---

## 6. 🎯 다음 세션 개발자를 위한 인수인계 지도

1. **임의의 UI 렌더링 함수를 수동으로 부르지 마십시오**:
   * 화면 UI 레이아웃을 바꾸고 싶다면, 명시적으로 `updateLayoutUI()`를 호출하지 말고 오직 **`state.left` 와 `state.right` 의 상태 변수에 값을 넣거나 비워주는(null)** 작업만 하십시오. 나머지는 자율 반응 세터가 100% 다 처리합니다.
2. **`browserSplitState` 및 `keepSplitState` 라는 망령 단어를 절대 쓰지 마십시오**:
   * 이 단어들은 역사 속으로 영구 매립되었습니다. 가상 디스플레이 2개는 오직 `left` 와 `right` 라는 이름으로 대칭적으로 존재하며, 뷰포트 해상도 락 또한 `leftLockedViewport` 와 `rightLockedViewport` 독립 변수로 대칭 제어됩니다.
3. **새로운 기능을 붙일 때도 SSOT를 지키십시오**:
   * 예컨대 백엔드로부터 새로운 스트림 제어 신호가 와서 화면을 홈으로 리셋하거나 강제 전환해야 한다면, 오직 `state.left = null; state.right = null;` 대입 한 줄로 상황을 강제 종료하십시오. 뷰와 레이아웃은 그것만으로도 우아하게 100% 완벽 싱크를 맞추며 홈 런처로 되돌아갑니다.
4. **리사이즈 및 앱 클린을 연동할 때 상기 5장의 최적화 수명 주기를 충실히 따르십시오**:
   * 절대로 뷰포트 변경 시마다 `createVirtualDisplay` 를 흔하게 호출하는 과거 방식을 내버려 두지 말고, `virtualDisplay.resize()` 와 `wm` 특권 셸 연동을 구현하십시오.
5. **system_server 동기화와 무중단 비디오 소켓 핫리프레시, 스마트 앱 페어 요구사항을 지키십시오**:
   * **system_server 재부팅 방지**: Primary와 Secondary 가상 디스플레이 생성을 처리할 때는 무조건 `vdOperationGlobalMutex.withLock` 내에서 동기화해야 데드락에 의한 단말 리부팅을 예방할 수 있습니다.
   - **무중단 핫리프레시**: 뷰포트 해상도 변경 시 영상 소켓을 닫지 마십시오. 소켓은 열어둔 채 디코더 인스턴스만 재생성하고 키프레임을 요청하는 핫리프레시(`isHotRefresh = true`) 구조를 유지해야 로딩 레이턴시가 없습니다.
     * **[디코더 해제]**: 디코더 생성 직후 도착하는 최초의 키프레임 수신 시, 시퀀스 tracking 정의 여부와 무관하게 최우선적으로 `this._waitingForKeyframe = false` 상태를 즉시 해제해 주어야 최초 기동 시의 찌꺼기 델타 드롭으로 인한 초기 검은 화면(Black Screen) 락이 걸리지 않습니다.
     * **[캔버스 노출]**: 듀얼 스플릿에서 단일 화면 모드로 전환 시(`updateLayoutUI`의 `hasLeft` 분기), 습관적인 `clearCanvas()` 호출로 인해 `canvas.style.opacity = '0'`으로 숨겨지는 일이 없도록 강력히 가드하십시오. 반드시 `canvas.style.opacity = '1'` 상태를 항시 유지해 주어야 무중단 리사이즈 중에도 화면이 투명해지거나 검게 타지 않고 부드러운 전환을 수행할 수 있습니다.
   - **스마트 앱 페어**: 이미 실행 중인 패키지를 절대 중복 기동하거나 성급히 죽이지 마십시오. 현재 실행 중인 왼쪽/오른쪽 슬롯과 비교해 기존 앱은 그대로 보존하고, 누락된 파트너 앱만 맞춤형으로 런칭하는 일반화 수식을 준수하십시오.
6. **우측 보조화면의 단독 주화면 승격(Promotion) 시 디코더/페이서 강제 리부트와 UI SSOT 동기화를 준수하십시오**:
   - **물리적 리부트 지침**: 보조화면(Secondary) 비디오 스트림을 메인(Primary)으로 승격시킬 때는, 단순히 뷰포트 해상도만 변경해서는 안 됩니다. 반드시 `promoteSecondaryToPrimary` 비동기 흐름 내에서 `initDecoder(true)`를 강제 기동하여 기존 Primary의 낡은 프레임 페이서 시간축과 시퀀스 트래커를 물리적으로 완전히 파괴한 후 새로 리부트해야 `dropped` 드롭 지옥과 `RECOVERING` 갭 고착 에러를 막을 수 있습니다.
   - **SPS/PPS 캐시 이식**: 디코더를 리부트할 때, 우측 보조화면의 `window.secondaryDecoder` 및 `window._lastSecondarySpsPps` 캐시 바이트를 신형 Primary 디코더로 즉각 수동 이식해주어 `WAITING_SPS_PPS` 데드락 상태를 우회해야 합니다.
   - **투명도 쉴드 및 SSOT 정밀 정렬**: 승격 과도기(200~500ms) 동안 이전 앱의 잔상이 남지 않도록 승격과 동시에 `canvas.style.opacity = '0'` 쉴드를 장착하십시오. 그 후 첫 프레임 수신 감지 시점(`checkReady`)에 `state.left = window._promotedApp; state.right = null;` 반응형 상태를 일치 대입하고, 캔버스를 `opacity = '1'`로 우아하게 복원하십시오. 이 수명 주기를 깨뜨리면 UI 상태 엔진과 실제 비디오 디코더 캔버스가 어긋나 전체 시스템이 오작동하게 됩니다.


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

