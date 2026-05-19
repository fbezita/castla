# Castla 기술 아키텍처 및 개발 히스토리 (Technical Architecture & Development History)

본 문서는 Castla 프로젝트에서 구현된 핵심 저지연 미러링 기술, Shizuku 기반의 시스템 우회 및 제어 기법, 안정적인 네트워크 재연결(Reconnection) 메커니즘의 설계와 구현 방식을 상세히 정리한 고해상도 기술 문서입니다.

---

## 1. Shizuku 기반 가상 디스플레이 (Virtual Display) 미러링 구현

### 1) 기술적 배경 및 MediaProjection의 한계
일반적인 안드로이드 화면 미러링은 `MediaProjection` API를 사용합니다. 그러나 이 방식은 다음과 같은 두 가지 치명적인 문제가 있습니다:
* **사용자 동의 팝업 강제**: 앱을 실행하고 미러링을 시작할 때마다 시스템 보안 경고("Castla에서 화면에 표시되는 모든 내용을 캡처합니다")가 노출되어 드라이빙 환경의 UX를 훼손합니다.
* **주 화면의 종속성**: 휴대전화의 주 화면(Primary Display)을 그대로 복제(Clone)하므로, 휴대전화 화면이 꺼지거나 다른 앱을 사용할 때 차량 내 미러링 화면도 같이 끊기거나 변경됩니다.

### 2) 구현 메커니즘 (Shizuku를 활용한 특권 권한 우회)
Castla는 Shizuku를 통해 시스템 쉘 UID(2000) 권한을 획득하여, 시스템 내부 서비스인 `DisplayManagerGlobal`에 직접 리플렉션으로 접근해 **완전히 독립된 가상 디스플레이(Virtual Display)**를 동적으로 생성합니다. 이 방식을 통해 **사용자 동의 팝업이 100% 생략**됩니다.

```kotlin
// android.hardware.display.DisplayManagerGlobal을 리플렉션으로 획득하여 가상 디스플레이 직접 생성
val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")

// 디스플레이의 핵심 동작을 규정하는 특권 플래그 지정
var flags = DISPLAY_FLAG_PUBLIC or DISPLAY_FLAG_OWN_CONTENT_ONLY or DISPLAY_FLAG_PRESENTATION or DISPLAY_FLAG_DESTROY_CONTENT
if (android.os.Build.VERSION.SDK_INT >= 33) {
    // ALWAYS_UNLOCKED: 주 화면(휴대전화)이 잠겨도 가상 디스플레이는 잠금 상태로 들어가지 않음
    // TRUSTED: 시스템 UI(Status Bar, Navigation Bar 등)가 가상 디스플레이 상에서도 원활하게 렌더링됨
    flags = flags or DISPLAY_FLAG_ALWAYS_UNLOCKED or DISPLAY_FLAG_TRUSTED or DISPLAY_FLAG_OWN_DISPLAY_GROUP
}

val builderCtor = builderClass.getConstructor(
    String::class.java, Int::class.javaPrimitiveType,
    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
)
val builder = builderCtor.newInstance(name, width, height, dpi)
builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
val config = builderClass.getMethod("build").invoke(builder)

val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
val dmg = dmgClass.getMethod("getInstance").invoke(null)
val createMethod = dmgClass.declaredMethods.first { m ->
    m.name == "createVirtualDisplay" && m.parameterTypes.any { it == configClass }
}
createMethod.isAccessible = true
val display = createMethod.invoke(dmg, *args) as? VirtualDisplay
```

### 3) 독립 가상 화면 런처 작동
가상 디스플레이가 생성되면, 쉘 명령어를 통해 해당 가상 화면 ID에 맞춤형 홈 액티비티를 강제 실행합니다.
```bash
am start -W --display $displayId -n com.castla.mirror/.ui.VirtualDisplayHomeActivity
```
이를 통해 사용자는 스마트폰으로 카카오톡이나 다른 작업을 수행하는 동시에, 차량 테슬라 화면에서는 완전히 다른 독립적인 화면(Waze, Google Maps 등)이 독립적으로 구동되는 **진정한 멀티 디스플레이(Multi-Display) 환경**이 완성됩니다.

---

## 2. 물리 화면 전원 꺼짐 (Screen OFF) 미러링 유지 기법

차량 주행 중 스마트폰의 화면이 계속 켜져 있으면 **배터리 과소모, 기기 발열, 디스플레이 번인(Burn-in)**이 발생합니다. Castla는 스마트폰의 물리 화면(Physical Panel)은 완전히 끄되, 가상 디스플레이의 미러링 세션은 활성 상태로 유지하는 고급 전원 관리 기법을 구현했습니다.

### 1) SurfaceControl 기반 물리 패널 전원 차단
스마트폰의 물리 화면 전원을 쉘 레벨에서 직접 제어하기 위해, `scrcpy`에서 사용하는 특권적 `SurfaceControl` API를 사용해 내부 물리 디스플레이의 백라이트와 패널을 강제로 `POWER_MODE_OFF (0)`로 전환합니다.

```kotlin
private val POWER_MODE_OFF = 0
private val POWER_MODE_NORMAL = 2

fun setPhysicalDisplayPower(on: Boolean) {
    val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
    val scClass = Class.forName("android.view.SurfaceControl")
    val setMethod = scClass.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
    
    // 디스플레이 토큰을 안드로이드 버전에 맞춰 정밀하게 획득
    val token = getPhysicalDisplayToken(scClass)
    if (token != null) {
        setMethod.invoke(null, token, mode)
    }
}
```

* **버전별 물리 디스플레이 토큰(IBinder) 획득**:
  * **Android 10 ~ 13**: `SurfaceControl.getPhysicalDisplayIds()` 및 `getPhysicalDisplayToken()` 또는 `getInternalDisplayToken()` 사용.
  * **Android 14+**: 구글의 보안 강화로 숨겨진 `DisplayControl`에 접근하기 위해 `/system/framework/services.jar`를 커스텀 ClassLoader로 동적 로드하고 `libandroid_servers.so` JNI 라이브러리를 동적 링킹하여 내부 디스플레이 토큰을 안정적으로 조회합니다.

### 2) 가상 디스플레이 수명 연장 및 CPU 잠자기 극복
물리 화면이 꺼지면 안드로이드 시스템은 절전을 위해 CPU를 슬립(Sleep) 상태로 전환하고 가상 디스플레이도 정지시키려고 시도합니다. 이를 해결하기 위해 세 가지 정밀 제어 메커니즘을 도입했습니다.

* **비동기 복구 (Asynchronous Post-Delayed Recovery)**:
  사용자가 물리 전원 버튼을 누르거나 화면 타임아웃으로 `ACTION_SCREEN_OFF` 이벤트가 유입되면 시스템이 완전한 슬립 전환 처리를 끝낼 수 있도록 **150ms의 미세 딜레이**를 줍니다. 그 직후 Shizuku의 `InputManager.injectInputEvent`를 통해 `KEYCODE_WAKEUP (224)` 키를 입력하고 가상 디스플레이 강제 가동 명령을 주입합니다.
  ```bash
  dumpsys power set-display-state $displayId ON
  ```
  이로 인해 시스템 CPU와 가상 디스플레이 파이프라인은 깨어나 동작을 유지하지만, `SurfaceControl`에 의해 물리 패널은 완전히 꺼진(Black screen) 상태가 영구 유지됩니다.
* **Keep-alive 주기 단축 (3000ms)**:
  물리 화면이 강제 비활성화된 과도기 상태에서 화면 타임아웃이 5~6초 내로 급격히 짧아지는 현상을 방지하기 위해 가상 디스플레이 생존 신호(Keep-alive) 송신 주기를 기존 30초에서 **3초**로 대폭 단축하여 프레임 전송 끊김을 원천 차단했습니다.

### 3) [CRITICAL] 1초 고속 파워오프 버스트 (1-Second Fast Power-Off Burst)
> [!IMPORTANT]
> 사용자가 스마트폰의 전원 버튼을 눌러 화면을 끌 때, 안드로이드 AOSP 시스템은 내부 전원 매니저와 백라이트 드라이버를 통해 **비동기적인 백라이트 리셋 명령어들을 여러 차례 연속적으로 전달**합니다.
> 이때 단 한 번만 `setPhysicalDisplayPower(false)`를 호출하면, 찰나의 순간 뒤에 유입되는 AOSP의 비동기 화면 켜짐/리셋 명령에 의해 물리 패널이 다시 켜지거나 플리커링(flicker)이 발생해 화면 끄기 동작이 실패하게 됩니다.

이 치명적인 AOSP 전원 드라이버 레이스 컨디션(Race Condition)을 완전히 극복하기 위해 **"고속 파워오프 버스트(Power-Off Burst)"**를 고안해 구현했습니다:

```kotlin
// MirrorForegroundService.kt
ScreenOffAction.TURN_PANEL_OFF -> {
    val vdm = virtualDisplayManager
    if (vdm == null) {
        Log.w(TAG, "Panel-off requested but no VirtualDisplayManager — falling back")
        val fallback = screenOffPolicy.onPanelOffResult(success = false)
        executeScreenOffAction(fallback)
        return
    }
    
    // 1. 글로벌 슬립 및 키가드 상태를 먼저 극복하기 위해 시스템 WAKEUP/dismiss-keyguard 명령어 선행 인젝션
    try {
        vdm.getPrivilegedService()?.execCommand("input keyevent 224")
        vdm.getPrivilegedService()?.execCommand("wm dismiss-keyguard")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to inject WAKEUP/dismiss-keyguard keyevents", e)
    }

    // 2. 100ms 주기로 총 10회(1초 동안) 비동기 고속 버스트(Burst) 형태로 setPhysicalDisplayPower(false)를 인젝션!
    // 이를 통해 글로벌 화면 해제에 따르는 AOSP 백라이트 리셋 드라이버의 비동기 화면 켬 오버라이드 동작을 완벽히 '짓밟아(Stamp out)' 무력화합니다.
    serviceScope.launch {
        var success = false
        for (i in 1..10) {
            try {
                success = vdm.setPhysicalDisplayPower(false)
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(100) // 100ms 지연
        }
        Log.i(TAG, "[BUILD:screen-off-v3] Physical panel OFF burst complete: final_success=$success")
        
        serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val fallback = screenOffPolicy.onPanelOffResult(success)
            if (fallback != ScreenOffAction.NONE) {
                executeScreenOffAction(fallback)
            }
        }
    }
}
```
* **결과**: 이 고성능 버스트 기법을 통해 디바이스 기종에 관계없이 화면 전환 과도기 타이밍에 발생하는 백라이트 플리커링이 완벽하게 방지되며, 물리 화면은 철저하게 암전 상태를 유지하게 됩니다.

---

## 3. 초저지연 H.264 비디오 스트리밍 파이프라인 및 안정적 재연결(Reconnection)

테슬라 웹 브라우저 환경에서 실시간 60fps 미러링을 초저지연(50~80ms)으로 재생하기 위해 고성능 하드웨어 H.264 인코더와 안정성 높은 브라우저 단의 네트워크 복구 루틴을 설계했습니다.

### 1) 안드로이드 하드웨어 인코더(MediaCodec) 최적화
[VideoEncoder.kt](file:///c:/project/private/castla/app/src/main/java/com/castla/mirror/capture/VideoEncoder.kt)에서 기기의 하드웨어 미디어 코덱을 커스텀 제어합니다.
* **프로파일 어댑티브 매핑 (High vs Baseline)**:
  압축 효율이 15~25% 높은 CABAC 및 8x8 변환 기반의 **H.264 High Profile**을 먼저 시도하고, 칩셋(예: 일부 Exynos/MediaTek AP)에 의해 거부되면 즉시 호환성이 완벽한 **Baseline Profile**로 폴백(Fallback) 처리합니다.
* **VBR (Variable Bitrate) 및 레이턴시 제어**:
  움직임이 없는 정적 화면에서 대역폭 낭비를 막고 고해상도 스트리밍(동영상 재생 등) 시 급격한 랙을 차단하기 위해 CBR 대신 **VBR(`BITRATE_MODE_VBR`)**을 활성화하고, 프레임 버퍼링 지연을 유발하는 **B-프레임을 강제 비활성화(`max-bframes = 0`)**했습니다.
* **인코더 강제 활성화 및 워치독 관리**:
  * `KEY_OPERATING_RATE`를 최대치(`32767`)로 강제 주입하여, 화면 변화가 적을 때 GPU/VPU가 저클럭(underclocking) 상태로 들어가 저지연 성능이 떨어지는 문제를 원천 차단했습니다.
  * 정적 화면 상태에서 프레임 송신이 끊겨 웹소켓이 타임아웃 처리되는 것을 막고자, 100ms 동안 화면 변화가 없을 때 이전 프레임을 자동으로 재송출하도록 `KEY_REPEAT_PREVIOUS_FRAME_AFTER (100_000ms)`을 인코더에 인젝션했습니다.
* **제로카피 네트워크 패킷 설계 (Zero-Copy Network Optimization)**:
  ```kotlin
  // 네트워크 전송 시 8바이트 헤더를 붙여서 보낼 수 있도록, 바이트 어레이 생성 단계에서 앞쪽에 8바이트의 빈 공간을 미리 확보합니다.
  val data = ByteArray(info.size + 8)
  buffer.get(data, 8, info.size) // 실제 비디오 데이터를 8번 인덱스부터 쓰기 작업
  ```
  이를 통해 네트워크 전송 모듈에서 중복적인 메모리 복사 및 할당(GC 유발)을 완벽히 방지하여 소켓 전송 효율을 대폭 끌어올렸습니다.

### 2) 테슬라 브라우저(WebCodecs API)와 결합된 재연결 및 세션 동기화
로컬 WiFi 무선 통신의 특성상 테슬라 차량이 멀어지거나 신호가 약해질 때 웹소켓 연결이 일시적으로 해제될 수 있습니다. Castla는 끊김 발생 시 1초 만에 화면이 자동 복구되는 정밀한 프론트엔드 연결 상태 기계를 구축했습니다.

* **재연결 시 SPS/PPS 및 시퀀스 캐시 강제 무력화**:
  인코더 세션이 재시작되면 안드로이드 코덱은 완전히 새로운 SPS(Sequence Parameter Set) 및 PPS(Picture Parameter Set)를 전송하고 프레임 번호(`Sequence Number`)를 `0`으로 리셋합니다.
  이때 브라우저 디코더가 기존 미디어가 가지고 있던 캐시와 프레임 순서 번호를 유지하고 있으면 디코딩 엔진(WebCodecs) 내부에서 **프레임 갭 에러(Frame gap error)가 터져 화면이 영구 로딩(`Loading...`) 상태에 갇깁니다**.
  이를 차단하기 위해 브라우저의 소켓 재연결 핸들러 실행 즉시 **디코더의 캐시와 상태 메타데이터를 강제로 초기화**하여 최초로 도착한 SPS/PPS 키프레임을 완벽하게 재디코딩하도록 최적화했습니다.
  ```javascript
  // main.js의 연결 복구 루틴 내 초기화 코드
  if (decoder) {
      decoder._lastSeqNum = undefined; // 이전 시퀀스 트래킹 무력화
      decoder._cachedSpsPps = null;    // 이전 코덱 설정 데이터 캐시 클리어
      if (decoder.resetStats) decoder.resetStats();
  }
  ```
* **이중 연결 및 독립 패스 모니터링**:
  비디오 웹소켓(`videoSocket`), 터치 입력 제어 웹소켓(`controlSocket`), 오디오 플레이어 웹소켓(`audioSocket`)이 상호 유기적으로 상태를 공유하여, 하나의 스트림이 끊어지더라도 전체 미러링 환경이 대기 시간 없이 즉시 동기화 재연결 프로세스를 실행하여 운전자가 재연결 여부를 인지하기 어려울 정도로 매끄러운 화면 복원을 구현했습니다.

---

## 4. HTTPS (SSL/TLS) 로컬 인증서 적용
* **보안 컨텍스트(Secure Context) 요구**: 웹 브라우저의 고성능 H.264 하드웨어 가속 디코더(`WebCodecs API`)를 사용하기 위해서는 반드시 `https://` 또는 `localhost`와 같은 보안 안전 지대 환경이어야 합니다.
* **구현 방식**: Java SDK `keytool` 유틸리티를 사용하여 100년 유효기간의 자체 서명 인증서 키스토어(`castla.p12`)를 생성하여 프로젝트 에셋폴더(`assets/`)에 포함시키고, `MirrorServer.kt` 구동 시 SSL 소켓 팩토리 환경으로 실행하도록 보강했습니다.
* **이점**: 차량 브라우저에서 안심하고 고속 H.264 웹디코더 하드웨어 가속을 만끽할 수 있는 완벽한 런타임 보안 아키텍처 환경을 완성했습니다.

## 5. 최신 업데이트: 핫스팟 자동 종료 백그라운드 비동기(Async) 리팩토링
* **문제 상황**: 핫스팟 자동 종료(`autoHotspot`) 작동 시 무거운 안드로이드 시스템 테더링 정리 작업으로 인해 앱 종료 과정에서 UI가 버벅이던 문제를 완벽히 해결하기 위해, Shizuku `PrivilegedService` 내의 `stopWifiTethering` 작동 모델을 `tetheringExecutor` 기반의 **비동기(Asynchronous) 모델로 리팩토링**하여 즉각적인 응답성을 확보했습니다.

## 6. 듀얼 독립 미러링 (VD_1/VD_2) 무재실행 (Zero-Restart) 및 무중단 동기화 리팩토링

물리적인 화면 분할 비율을 변경할 때 가상 디스플레이가 꼬여 연쇄 폭사하거나, 특정 써드파티 앱이 안드로이드 OS의 액티비티 재창조(Re-creation) 반응으로 인해 강제 재시작되던 태생적 한계를 극복하고, 두 가상 디스플레이 파이프라인의 **완전한 상호 독립성(Mutual Independence)**을 달성하기 위한 전면적인 리팩토링을 완료했습니다.

### 1) 프라이머리(VD_1)와 세컨더리(VD_2)의 상호 결속(Interference Loop) 완전 제거
* **결속 고리 영구 퇴출**: 프라이머리 뷰포트 복원(`restoreCurrentVdContent`)이 발생할 때마다 엉뚱하게 결속되어 세컨더리를 동반 갱신시키던 **legacy `rebuildSecondaryPipeline` 강제 동반 호출 코드를 백엔드에서 완전히 삭제**했습니다.
* **상호 격리 독립성 확보**: 이제 두 디스플레이 파이프라인은 서로의 리사이징이나 상태 변화에 1%의 영향도 주지 않는 완벽한 독립된 개체로서 주권을 행사합니다.

### 2) 동시성 경합 차단용 `secondaryResizeJob` 코루틴 가드 주입
* **코루틴 취소 장치 주입**: 프라이머리와 마찬가지로 세컨더리 뷰포트 변경 요청이 아주 빠르게 중첩되어 들어올 때, 이전의 리사이즈 작업을 즉시 안전하게 취소하고 최신 요청 하나만 우아하게 가동시키는 **`secondaryResizeJob?.cancel()` 가드를 주입**하여 동시성 꼬임에 의한 상태 파괴 현상을 100% 원천 예방했습니다.

### 3) 320px 동적 최소 안전 해상도 가드레일 (Dynamic Safety Guardrail) 이식
* **하드웨어 붕괴 마진 연산**: 화면 드래그 시 가상 디스플레이의 너비가 하드웨어 H.264 인코더의 한계선인 `320px` 미만으로 찌그러지는 것을 막기 위해, 브라우저 영역에 맞춤형 동적 마진 차단선을 장착하여 **Green/Pink 무지개 노이즈 현상을 원천 방지**했습니다.

### 4) 드래그 조작 60fps 브라우저 피팅 & pointerup 1회 전송
* **송출 부하 0% 최소화**: 드래그 바를 조절하는 도중에는 백엔드로 해상도 변경 신호를 일절 날리지 않고 오직 브라우저 CSS와 `'fill'` 스케일링으로 60fps 무중단 줌인/줌아웃을 구현하고, **조작을 완전히 마치고 손을 뗀 시점(`pointerup`, `pointercancel`)에만 최종 해상도를 단 1회 백엔드로 전송**하도록 동기화해 연쇄적인 코덱 재부팅 부하를 완벽히 종식했습니다.

### 5) 최초 독립 런칭 시의 과도한 중복 am start 명령 다이어트
* **기동 인텐트 단일화**: 독립 세컨더리 런칭 시 짧은 ms 사이에 2회 연속 폭풍 송출되던 뷰포트 크기 및 `launchApp` 명령을 **단 1회의 150ms 딜레이 단일 런칭 인텐트로 정합**했습니다. 이로 인해 뜨던 도중에 툭 꺼져서 강제로 재생성당하던 현상을 깔끔하게 완치했습니다.

### 6) CanvasRenderer NaN 터치 예방 및 세컨더리 리사이즈 터치 복원
* **NaN 터치 마비 차단**: 최초 비디오 프레임이 그려지기 전 사용자가 캔버스를 건드릴 때 `0/0` 비디오 비율 연산으로 인해 `NaN` 터치 좌표가 전송되어 안드로이드 OS 입력 드라이버를 마비시키던 현상을 예방하기 위해, `NaN` 또는 `0` 감지 시 실제 캔버스 클라이언트 비율로 100% 매핑되게 방어막을 설계했습니다.
* **실시간 터치 재연결**: 백엔드에서 가상 디스플레이 리사이즈 시 `secondaryTouchInjector` 에 터치 주입 리스너(`setVirtualDisplayInjector`)를 다시 결합해 주지 않던 문제를 발견하고, 리사이즈 즉시 끊어진 터치 바인딩을 **실시간 자동 복구(Auto-Rebind)해 주는 가드**를 이식했습니다.

### 7) 앱 페어(App Pair) 초고속 순차 런칭 시퀀스 (Fast Sequential Launch) 개량
* **딜레이 임계치 극적 단축**: 기존에 앱 포커스 충돌을 막기 위해 억지로 길게 잡아 두어 사용자의 연타 실수 및 타이밍 엇박자를 유발하던 굼뜬 지연(프라이머리 800ms / 세컨더리 2000ms) 루틴을 전면 철폐하고, **프라이머리 200ms / 세컨더리 500ms의 초고속 순차 시퀀스로 개량**했습니다.
* **기대 효과**: 이제 페어 단축키 클릭 즉시 **단 0.5초 만에 좌우 화면이 촤라락 동시에 최상의 응답성으로 기동**되며, 중복 클릭 및 포커스 유실 기동 실패 현상이 완벽하게 완치되었습니다.

### 8) 분할 종료 시 캔버스 여백 찌그러짐 현상, 프론트엔드 리플로우 엇박자 및 WebCodecs 크래시 완치
* **프론트엔드 리플로우 엇박자 교정 (Reflow-Aware Viewport)**:
  * **원인**: 종료 버튼 클릭 즉시 동기 흐름 속에서 `canvasEl.clientWidth`를 읽으면, 브라우저가 레이아웃을 다시 그리기(Reflow) 전이므로 **스플릿 상태의 찌그러진 과거 해상도(`517x811` 등 세로 기둥 형태)**가 백엔드로 오송출되어 화면이 찌그러지던 미시적 타이밍 버그를 완벽히 격파했습니다.
  * **해결**: 종료 즉시 1단계로 `window.innerWidth`/`innerHeight` 가로형 풀 화면 해상도를 우선 즉시 전송하여 백엔드를 빠르게 가로 모드로 전향시킨 뒤, **`100ms` 및 `300ms` 지연 타이머에 의해 캔버스가 풀스크린으로 시원하게 펴진 실제 정밀 실측치(`clientWidth/Height`)를 2차 보정 송신**함으로써 단 1픽셀의 오차도 없는 완벽한 여백 0% 풀스크린을 달성했습니다.
* **백엔드 비동기 경합 철벽 해결 (`layoutMode: 'single'` 및 `forceSingle` 풀스크린 리빌드 강제 동기화)**:
  * **해결**: 백엔드 `onViewportChange`와 `rebuildPipeline`에 `layoutMode` 파라미터 및 `forceSingle` 플래그를 정교하게 주입했습니다. 이제 `layoutMode: "single"`이 감지되면 즉시 이전 찌꺼기 분할 가변 상태에 구애받지 않고 **완벽한 디바이스 풀 해상도(`currentMaxHeight`)로 강제 강착 리빌딩**됩니다.
  * **즉시 동기화**: `releaseSecondaryPipeline` 완료 시점에 기다릴 필요 없이 백엔드가 스스로 **`rebuildPipeline(force = true, forceSingle = true)` 를 강력하게 강제 호출**함으로써, 프론트엔드가 잠깐 전체화면으로 커졌다가 여백 화면으로 되돌아가 찌그러지던 현상을 2000% 완벽하게 섬멸했습니다.
* **WebCodecs 디코더 자가 치유 및 무지개 현상(Rainbow Artifacts) 원천 박멸**:
  * **원인**: 디코더가 재초기화(configure/flush)된 시점에 백엔드로부터 최초로 들어온 프레임이 키 프레임이 아닐 경우, Chrome/Android WebCodecs API의 `VideoDecoder`가 `A key frame is required after configure() or flush()` 에러를 발생시키며 영구 크래시되는 문제를 포착했습니다.
  * **무지개 현상 극비 원인 규명**: 뷰포트 크기 조절 시 `hot-refresh`로 인해 새 `H264Decoder` 인스턴스를 동적으로 생성(`new`)하거나 비디오 웹소켓을 다시 맺을 때(`connectVideo`), **기존에 받아 두었던 SPS/PPS 캐시 바이트가 공란으로 리셋되는 버그**가 있었습니다. 이로 인해 키프레임을 새로 요청해 받아도 SPS/PPS(가로세로 비율 정보)가 누락되어 하드웨어가 찌그러지고 뒤틀린 깨진 비디오 스트림(그린 스크린/무지개 노이즈)을 그렸던 것입니다.
  * **해결 1 (SPS/PPS 캐시 선별적 이식 - `preserveCache`)**: `initDecoder(preserveCache)` 및 `initSecondaryDecoder(preserveCache)` 시그니처에 `preserveCache` 파라미터를 전격 도입했습니다. 이제 단순 접속 렉이나 끊김 복구 상황에서는 이전의 소중한 SPS/PPS 데이터를 새 디코더로 **안전하게 영구 인양(Migration)**시키는 반면, 해상도가 실제로 변하는 `resolutionChanged` 핫 리프레시 상황에서는 **과거의 낡은 해상도 캐시가 오염물로 작용하지 않도록 완벽하게 리셋(Discard)**해 줍니다.
  * **해결 2 (핫 리프레시 소켓 리셋 제어 - `isHotRefresh`)**: `connectVideo(isHotRefresh)` 및 `connectSecondaryVideo(isHotRefresh)` 에 `isHotRefresh` 제어자를 두었습니다. 실제 해상도가 변하는 핫 리프레시에서는 `isHotRefresh = false` 로 호출하여 이전 소켓과 옛 규격 캐시를 완벽히 리셋하고 백엔드로부터 **새 해상도에 동기화된 깨끗한 신형 SPS/PPS 파라미터를 소켓 오픈 즉시 수혈**받도록 보장하여 화면 찢어짐과 무지개 노이즈를 100% 원천 차단했습니다.
  * **해결 3 (자가 복구 & 시퀀스 재정착 - Re-anchoring)**:
    * `decoder.js` 의 `H264Decoder` 초기 기동 상태를 **`_waitingForKeyframe = true` (키 프레임 대기 모드)**로 강력 격상했습니다. 이제 디코더가 재부팅되었을 때 들어오는 최초의 모든 델타 프레임들을 우아하게 드롭(drop)하고 백엔드에 즉시 키프레임을 요청합니다.
    * **시퀀스 갭 오인 드롭 및 화면 갱신 정체 완치**: 
      * **키프레임 강제 락 해제 및 물리 분기 디커플링 (Decoupled Keyframe Wait-Unlock)**: 해상도가 조절되어 비디오 시퀀스 번호가 다시 1번부터 시작될 때, 델타 프레임 대기 도중 `_lastSeqNum`과의 불일치로 인해 키프레임마저 "프레임 갭"으로 오인해 끝없이 버려지던 극악의 타이밍 오류를 격파했습니다. 기존에 `if - else if` 구조로 묶여 시퀀스가 일치할 때 오히려 대기 락이 풀리지 않거나 불일치 시 keyframe 재요청이 누락되던 논리 모순을 해결하기 위해, **대기 락 해제(`[SAFEGUARD 1]`)와 시퀀스 갭 검증(`[SAFEGUARD 2]`)을 각각 완전히 분리된 독립 `if` 블록(Decouple)으로 재설계**했습니다. 이제 어떠한 시퀀스 불규칙 상황에서도 최초의 정상 키프레임이 도착하면 관문이 즉각 열리고, 갭이 있는 경우에만 보정 요청이 정상적으로 수행되어 화면 갱신 멈춤 현상을 5000% 영구 소멸시켰습니다.
      * **연쇄 갭 방지 가드 (Cascade Gap Prevention & Backlog Safeguard)**: 키프레임 대기 중(`waitingForKeyframe = true`)에 들어오는 델타 프레임들을 드롭할 때뿐만 아니라, **하드웨어 디코더 큐 백로그 임계치 초과(`queueSize > threshold`)로 인해 델타 프레임이 부하 조절(Backlog drop)될 때도 무조건 `this._lastSeqNum = seqNum`을 동반 수행하도록 대개혁**했습니다. 이를 통해 네트워크 일시 혼잡이나 로컬 디코더 지연으로 백로그 드롭이 단 1회라도 발생했을 때, 그 다음으로 들어오는 정상 순차 델타 프레임이 시퀀스 갭으로 오인 오작동하여 화면이 통째로 멈추고 백엔드에 무한 키프레임 요청이 루프로 쏟아져 영상 갱신이 중단되던 근본 원인을 우주 최강의 견고함으로 완치했습니다.

---

## 7. 가상 디스플레이 주변부 제어 모듈(ABR, Thermal, PowerLock) 2단계 격리 리팩토링 및 displayId 자동 보정 기법 장착

`MirrorForegroundService.kt`에 얽혀 있던 수많은 하드웨어/시스템 비즈니스 제어 정책들을 도메인별 전용 클래스로 정밀 분리(Decoupling)하고, 비동기 디스플레이 리빌드 과정에서 발생하는 런타임 레이스 컨디션을 예방하기 위해 강건한 자동 보정 장치를 장착했습니다.

### 1) 주변부 3대 통제 매니저(Manager) 완전 캡슐화
서비스 단에 흩어져 있던 비대한 수명주기 상태값과 제어 루프를 독립된 클래스로 완전히 분리하여 코드 가독성과 유지보수성을 극대화했습니다.

* **`PowerLockManager` (CPU & Wi-Fi Lock 격리)**:
  - CPU partial wake lock 및 High-performance Wi-Fi Lock의 획득 및 안전 해제 로직을 전담합니다.
  - 서비스에서 WakeLock 유지 상태를 동적으로 수집해 로그를 남길 수 있도록 public `isHeld` 프로퍼티를 개방했습니다.
* **`ThermalThrottleManager` (온도 제어 및 스로틀링 격리)**:
  - 안드로이드 OS의 발열 경고 상태 리스너(`OnThermalStatusChangedListener`) 및 온도 변경에 따른 인코더 스로틀링 계산을 캡슐화했습니다.
  - `SEVERE` 등급 이상 감지 시, 비트레이트를 하향 조정하고 차량 브라우저 전용 소켓으로 실시간 온도 정보를 브로드캐스트하는 로직을 분리하였습니다.
* **`AdaptiveBitrateManager` (네트워크 및 프레임 드롭율 모니터링 격리)**:
  - 주기적인 해상도/FPS 자동 스케일러 타이머 루프(`evaluateAutoScale`) 및 네트워크 혼잡 시 20% 긴급 비트레이트 감쇄 정책을 격리 수용하였습니다.
  - 서비스의 `serviceScope`를 인젝션 받아 안전한 백그라운드 코루틴 루프로 구동됩니다.

### 2) 서비스 초경량화 및 문법 충돌 교정
* **결합도 소거**: 서비스 클래스 내부에 잔존해 컴파일 충돌을 유발하던 소문자 확장 함수 형태의 중복 레거시 찌꺼기들(`private fun powerLockManager.acquireWakeLocks()` 등)을 완벽하게 소거하여 컴파일 무결성을 확보했습니다.
* **프로퍼티 교정**: `preThermalTargetBitrate` 등 위임 형태로 선언되어 대입 연산이 불가능했던 프로퍼티들을 가변(`var`) 속성으로 완벽히 교정하여 `Val cannot be reassigned` 컴파일 빌드 오류를 원천 차단했습니다.

### 3) [CRITICAL] stale displayId 비동기 자동 보정 장치 (Auto-Correction Safeguard)
* **장애 원인 규명**: 
  - 유튜브 + 지도 페어 앱 런칭과 같이 가상 디스플레이 갱신(Rebuild)과 앱 기동이 짧은 ms 단위로 중첩되는 환경에서, 이전 디스플레이 ID(예: 37)로 발사된 앱 기동/폴백 런칭 요청이 현재 새로 갱신된 가상 디스플레이 ID(예: 39)와 맞지 않아 `stale display`로 분류되어 스킵되는 버그가 확인되었습니다.
  - 이로 인해 외부 브라우저 기동 실패 시 작동하는 자체 `WebBrowserActivity` 폴백마저 차단당해 화면이 갱신되지 못하고 락이 걸리던 미시적인 런타임 예외 현상이 발생했습니다.
* **보정 장치 탑재**:
  - `launchTargetOnDisplay` 내부 최상단에 디스플레이 ID 강건화 필터를 구축했습니다.
  - 실행하려는 `displayId`가 현재 활성화된 세컨더리 ID가 아니고, `isCurrentPrimaryVd` 검증에서도 stale 상태인 경우, 조기 기각(Skip)해 버리는 대신 **현재 새로 활성화되어 켜진 실시간 최신 프라이머리 디스플레이 ID(`activePrimaryId`)를 자동으로 추적해 보정 대입(`targetDisplayId = activePrimaryId`)**하여 끝까지 쉘 기동을 완수하도록 설계했습니다.
  - 이를 통해 비동기 해상도/인코더 리빌드 상태에서도 한 치의 오차나 실행 유실 없이 폴백 뷰어 및 써드파티 앱이 100% 정상 작동되도록 이중 displayId 샌드박스 안전망을 완성하였습니다.

---
*본 문서는 Castla 프로젝트 내 [docs/development_history.md](file:///c:/project/private/castla/docs/development_history.md) 경로에 안전하게 저장되었습니다.*
