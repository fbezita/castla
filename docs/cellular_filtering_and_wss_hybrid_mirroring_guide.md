# 📡 [Castla] 셀룰러 IP 필터링 & HTTPS/WebCodecs 하이브리드 미러링 구현 가이드

본 문서는 Android 스마트폰의 핫스팟/와이파이 사설 IP 자동 추출 가드(통신사 셀룰러 IP 필터링) 및 테슬라 브라우저의 보안 컨텍스트(HTTPS) 제약을 돌파하기 위한 **Cloudflare 와일드카드 DNS 트릭 기반의 HTTPS/WebCodecs + HTTP/MSE 하이브리드 미러링 아키텍처**의 구현 내역 및 설정 가이드입니다.

사용자의 컴퓨터 환경이 변경되어 대화 기록이 연동되지 않는 상황을 위해, 모든 구조 설계와 코드 수정 내용, 설정 절차를 상세히 기록해 두었습니다.

---

## 🚀 1. 핵심 아키텍처 개요

테슬라 차량용 브라우저에서 화면 미러링을 원활하게 수행하기 위해서는 다음 두 가지 모드를 하이브리드로 완벽하게 스위칭해야 합니다.

```
                  ┌─────────────────────── Tesla Browser ───────────────────────┐
                  │                                                             │
                  │   [HTTPS Context]                           [HTTP Context]  │
                  │   car.fbezita.com                           Local IP Direct │
                  │          │                                         │        │
                  │    (WebCodecs w/ WSS)                        (MSE w/ WS)    │
                  ▼          ▼                                         ▼        │
           [WSS Connection]                                     [WS Connection] │
   wss://192-168-43-1.ip.fbezita.com:9090                ws://192.168.43.1:9090 │
                  │                                                             │
                  └──────────────────────────────┬──────────────────────────────┘
                                                 │
                                                 ▼
                                        [Android Device]
                                     MirrorServer (Port 9090)
```

### 과제 1: Android 10.x.x.x 셀룰러 대역 제거 및 와이파이/핫스팟 IP 최우선순위 추출
* **원인**: 통신사 기지국으로부터 할당받은 사설 대역 IP(`10.133.xxx.xxx` 등)가 우선적으로 검색되어 브라우저에서 직접 연결이 불가능해지는 현상.
* **해결**: 모바일 셀룰러용 인터페이스(`rmnet`, `ccmni`, `p2p`, `ppp`) 및 `10.`으로 시작하는 IP 주소 스캔을 원천 차단하고, 핫스팟 및 와이파이 어댑터(`ap0`, `softap0`, `wlan0`, `swlan0`) 대역인 `192.168.x.x` 및 `192.0.0.4` 주소를 최고 우선순위로 매핑.

### 과제 2: HTTPS 환경에서의 Secure Context 제약 극복 (도메인 & DNS 와일드카드 트릭)
* **원인**: 테슬라 브라우저에서 하드웨어 가속 비디오 디코딩 API인 `WebCodecs`를 사용하려면 반드시 **HTTPS(Secure Context)**에서 페이지가 기동되어야 합니다. 그러나 HTTPS 페이지에서는 보안 정책(Mixed Content Block)으로 인해 암호화되지 않은 로컬 웹소켓(`ws://192.168.43.1:9090`)에 연결할 수 없습니다.
* **해결**: **Cloudflare Wildcard NS Delegation**을 적용합니다.
  - Cloudflare DNS 설정에서 `ip.fbezita.com` 서브도메인을 `sslip.io` 네임서버로 위임(Delegation)합니다.
  - 브라우저가 `192-168-43-1.ip.fbezita.com`에 접근하면, `sslip.io` 네임서버가 이를 해독하여 로컬 IP인 `192.168.43.1`로 즉시 해석(A record)하여 다이렉트 통신을 유도합니다.
  - 서버 측(Android 폰)은 `*.ip.fbezita.com` 또는 `*.fbezita.com`에 대해 발급된 정식 와일드카드 SSL 인증서(`.p12` 또는 `.pem`)를 탑재하고 있어 브라우저가 보안 웹소켓(`wss://`) 통신을 에러 없이 깨끗하게 수립합니다.

---

## 🛠️ 2. Cloudflare DNS 설정 가이드 (중요)

도메인과 로컬 실시간 사설 IP 간 SSL 핸드셰이크를 매끄럽게 통과시키기 위해, **Cloudflare DNS 설정 페이지**에 로그인하여 아래와 같이 NS(Name Server) 레코드를 1개만 신규 추가해 주십시오.

* **레코드 유형**: `NS`
* **이름 (Name)**: `ip` (완전한 도메인은 `ip.fbezita.com`이 됩니다)
* **이름 서버 (Content)**: `ns-aws.sslip.io` (백업용으로 `ns-gce.sslip.io` 도 함께 추가 권장)
* **TTL**: `자동 (Auto)`

> [!TIP]
> 이 설정이 완료되면 임의의 IP 주소를 대시(`-`)로 구분한 `[A]-[B]-[C]-[D].ip.fbezita.com` 형태의 주소가 자동으로 사설 IP인 `A.B.C.D`로 매핑됩니다! 인터넷을 경유하지 않고 차내 무선랜/핫스팟 다이렉트 패킷으로 전송되므로 대역폭 낭비와 딜레이가 전혀 발생하지 않습니다.

---

## 📂 3. 작업 및 수정 완료된 소스코드 상세

시스템은 총 3개의 영역(안드로이드 앱, NestJS 백엔드 시그널링 서버, Svelte 5 뷰어 웹앱)에서 유기적으로 수정 및 추가되었습니다.

---

### Component A: Android App (`c:\project\castla`)

#### 1) 사설망 IP 전용 필터링 및 우선순위 스캐너 탑재
- **수정 파일**: [NetworkMonitor.kt](file:///c:/project/castla/app/src/main/java/com/castla/mirror/network/NetworkMonitor.kt)
- **변경 사항**:
  - `rmnet`, `ccmni`, `p2p`, `ppp` 등의 셀룰러 계열 가상 인터페이스를 전면 스킵.
  - `10.`으로 시작하는 모든 통신사 IP 후보군을 엄격하게 필터링.
  - `ap`, `softap`, `wlan`, `swlan` 이름의 인터페이스와 `192.168.x.x` 및 `192.0.0.4` IP 대역에 가중 우선순위 부여 (`Priority 25~30`).

#### 2) 시그널링 IP 등록 & UI 모드 가이드 분기 구현
- **수정 파일**: [MainActivity.kt](file:///c:/project/castla/app/src/main/java/com/castla/mirror/MainActivity.kt)
- **변경 사항**:
  - **`getUserId()`**: `Settings.Secure.ANDROID_ID`를 기반으로 한 고유 기기 식별자를 도출하여 다중 유저 환경 격리 수립 (백그라운드 IP 매핑 등록용).
  - **`registerPhoneIpWithSignalingServer(userId, ip)`**: 별도의 무거운 HTTP 라이브러리 없이 경량 Java 네이티브 `HttpURLConnection` 및 `kotlinx.coroutines` 비동기 태스크를 사용해 `https://car.fbezita.com/api/castla/register-ip` 로 기기의 실시간 사설 IP를 안전하게 업로드.
  - **`updateServerUrl()`**:
    - **`webCodecsEnabled == true`**: UI 안내 주소로 완전무결하고 깔끔한 정적 주소인 **`https://car.fbezita.com/castla`**를 유도하여 보안 HTTPS 환경으로 안착하도록 유도 (더 이상 주소에 `userId`를 노출하거나 전달하지 않음).
    - **`webCodecsEnabled == false`**: 기존 MSE(Media Source Extensions) 모드 전용 다이렉트 주소인 **`http://[IP]:9090`**을 제공하여 순수 로컬 망으로 바이패스.
  - **시그널링 연동 타이밍**: 미러링이 최초 기동되는 `launchMirrorService` 시점 및 백그라운드 스트리밍 중 네트워크 연결이 재정비되는 시점에 즉시 시그널링 등록 트리거.

---

### Component B: NestJS 백엔드 (`c:\project\tesla_manager`)

다중 접속 유저 간 상호 간섭이 발생하지 않도록, `userId`와 스마트폰의 `localIp`를 일대일 격리하는 백엔드 메모리 시그널링 API 서버를 추가 구축했습니다.

#### 1) 공인 IP 기반 자동 매핑 및 인메모리 시그널링 서비스 구축
- **수정 파일**: [castla.service.ts](file:///c:/project/tesla_manager/manager/src/tesla/castla.service.ts)
- **주요 기능**:
  - 스마트폰의 `userId`와 `ip`를 1대1로 관리하는 기존의 `ipMap` 외에도, 스마트폰과 테슬라 브라우저가 공유하는 공인 IP를 매핑하는 `publicIpMap`을 동시에 운용합니다.
  - 이를 통해 쿼리 파라미터가 없거나 디폴트 유저일 경우, 클라이언트의 공인 IP 주소를 매칭하여 폰의 사설 IP(`192.168.x.x`)를 자동으로 해석 및 반환해 줍니다.

#### 2) 스마트폰 IP 등록 및 조회용 REST API 컨트롤러 설계 (공인 IP 추출 포함)
- **수정 파일**: [castla.controller.ts](file:///c:/project/tesla_manager/manager/src/tesla/castla.controller.ts)
- **노출 엔드포인트**:
  - `POST /api/castla/register-ip` (Body: `{ userId: string, ip: string }`, Req) - 안드로이드 앱에서 실시간 IP를 등록할 때 요청 헤더(cf-connecting-ip, x-forwarded-for 등)로부터 공인 IP를 추출해 서비스에 자동 등록.
  - `GET /api/castla/get-phone-ip` (Query: `?userId=xxx`, Req) - 테슬라 뷰어 프론트엔드에서 기기 IP를 조회할 때 기기 ID가 전달되지 않으면 접속 브라우저의 공인 IP를 기반으로 자동으로 매핑된 사설 IP를 반환.

#### 3) 의존성 모듈 연동
- **수정 파일**: [tesla.module.ts](file:///c:/project/tesla_manager/manager/src/tesla/tesla.module.ts)
- **변경 사항**: `TeslaModule` 내의 `providers`, `controllers`, `exports` 항목에 각각 `CastlaService`와 `CastlaController`를 바인딩하여 백엔드 컴파일러가 인식하도록 연결 완료.

---

### Component C: Svelte 5 뷰어 웹앱 (`c:\project\tesla_manager`)

테슬라 웹 브라우저 내에서 초저지연 하드웨어 스트리밍 또는 범용 디코더 재생을 담당하는 반응형 뷰어 페이지를 Svelte 5 핵심 명세(Runes)로 구현했습니다.

- **신규 생성 파일**: [castla/+page.svelte](file:///c:/project/tesla_manager/viewer/src/routes/castla/%2Bpage.svelte)
- **핵심 탑재 기술**:
  1. **Svelte 5 Runes 반응형 상태 관리**: `$state`, `$derived`, `$effect` 구조로 비디오 프레임 메타데이터와 재생 통계를 밀리초 단위로 제어.
  2. **가속 및 폴백 자동 선택 (Active Mode Selector)**:
     - **WebCodecs 활성화 + HTTPS 보안 컨텍스트 + 브라우저 지원 시**: `VideoDecoder` 기동 및 `wss://[ip-dashes].ip.fbezita.com:9090/stream` 연결.
     - **MSE 강제 설정 또는 비보안 Context 시**: `ws://[ip]:9090/stream` 연결 및 `jmuxer` 패키저 활용 MSE 렌더링.
  3. **H.264 Annex-B 실시간 NAL 파서 내장**:
     - ArrayBuffer 스트림에서 NAL Start Code(`0x00000001` 또는 `0x000001`)를 검출하고 NAL unit type 5(IDR I-Frame) 여부를 실시간 판독하여 `EncodedVideoChunk` 생성 및 주입.
  4. **초저지연 MSE 디버퍼링 기술 (De-buffering Watchdog)**:
     - MSE 환경의 최대 단점인 재생 지연 누적(200ms~500ms)을 해결하기 위해 `video.buffered` 영역을 100ms 주기로 초정밀 트랙 감시.
     - 딜레이가 150ms를 초과하면 배속 재생(`video.playbackRate = 1.25`)을 구동하고, 400ms 이상 심화되면 버퍼 최외곽 단으로 미디어 렌더링 시점 강제 점프(Seek) 복귀 시스템 작동.
  5. **HUD 대시보드 & 프리미엄 UI**:
     - 현대적 다크 모드 글래스모피즘(Glassmorphism) 스타일 및 반응형 레이아웃 채택.
     - 실시간 수신 전송 대역폭(Mbps), 초당 렌더링 프레임수(FPS), 연결 지연시간(Latency), 미러링 해상도 데이터 실시간 시각화.

---

## 🛠️ 4. 검증 및 작동 시나리오

개발 로컬망 또는 실제 차량 핫스팟 연동 시 아래 절차를 통해 동작을 테스트하실 수 있습니다.

### 1단계: 안드로이드 환경 설정
1. Castla 앱의 설정창에서 **WebCodecs** 옵션을 켭니다 (HTTPS 모드로 자동 전환).
2. 스마트폰 핫스팟을 기동하고, PC 혹은 차량 브라우저를 해당 핫스팟 와이파이에 연결합니다.
3. Castla 앱에서 **미러링 시작** 버튼을 누릅니다.
4. 앱 화면에 안내되는 접속 주소가 정적인 **`https://car.fbezita.com/castla`** 주소로 바르게 나오며, `10.` 대역이 완전히 제거된 것을 확인합니다.
5. 이때 백업 로그(Logcat)에서 NestJS API 호출 결과(`Signaling server registration response code: 200` 및 공인 IP 자동 감출)가 성공적으로 들어왔는지 감시할 수 있습니다.

### 2단계: 웹 뷰어 및 재생 테스트
1. 접속 장치(차량 혹은 PC 브라우저)에서 안내된 **`https://car.fbezita.com/castla`** 주소로 직접 진입합니다 (파라미터가 전혀 필요 없음!).
2. 페이지가 로드되면서 자동으로 백엔드 NestJS에 `get-phone-ip` 요청을 보냅니다. 백엔드는 두 기기 간의 공유 공인 IP를 추적하여 스마트폰의 최적 사설 IP를 알아서 해독하여 전달합니다.
3. `wss://[ip].ip.fbezita.com:9090/stream` 와일드카드 웹소켓이 깨끗하게 붙으면서 즉각적으로 렉 없는 화면 전송이 구동됩니다.
4. 만약 HTTPS가 제공되지 않는 테스트 환경(또는 WebCodecs 지원 사양이 낮은 기기)이라면, 상단의 **MSE** 단추를 눌러 즉시 로컬 `ws://` MSE 하이브리드 트랙으로 실시간 전환되는 것을 확인하실 수 있습니다.

---

본 설계를 활용해 차량 및 모바일 기기 간 최고 품질의 프레임 연출과 보안 컨텍스트 에러의 완벽한 탈출을 만끽하십시오! 🟢
