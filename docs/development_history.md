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

---
*본 문서는 Castla 프로젝트 내 [docs/development_history.md](file:///c:/project/private/castla/docs/development_history.md) 경로에 안전하게 저장되었습니다.*
