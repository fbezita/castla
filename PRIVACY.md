# Castla Privacy Policy

## English

Castla is an open-source application without advertising, analytics, crash-reporting, or tracking SDKs. It does not create a user account or upload video and audio streams to a Castla cloud service.

### Data processed locally

Castla processes the following data on the Android device and in the connected browser to provide its features:

- video frames from Castla virtual displays;
- optional audio from selected application UIDs;
- touch and text input sent by the browser;
- installed application names and icons used by the launcher;
- notification title, text, sender, app name, and timestamp when notification access is enabled;
- local diagnostic logs.

Video, audio, input, and notification payloads are sent directly between the phone and browser over the active network connection. Notification history is held in browser memory for the current session. Diagnostic logs leave the phone only when the user explicitly shares them.

### External connections

Castla can make these external network requests:

- a release check to the GitHub API;
- relay/DNS registration to `car.fbezita.com`, containing a shortened hash derived from `ANDROID_ID` and the selected local IP, a generated hostname, the local IP, and relay URL;
- normal DNS and HTTPS requests needed to reach those services.

The relay registration makes the browser address discoverable; it does not carry the video, audio, touch, or notification stream. As with any HTTPS request, service operators and network providers may observe standard connection metadata such as the source public IP and request time.

### Permissions and special access

| Access | Purpose |
|---|---|
| Shizuku | Create virtual displays, route tasks, inject input, and configure display behavior |
| Microphone/audio capture | Capture selected app audio when audio streaming is enabled |
| Local network, Wi-Fi, and hotspot | Connect the phone and Tesla browser |
| Bluetooth | Optional Tesla proximity and connection detection |
| Installed apps | Populate the virtual-display launcher |
| Notification access | Optional notification overlay and in-session history |
| Foreground service and wake lock | Keep an active mirroring session running |
| Install unknown apps | Open or install a Shizuku APK when the user chooses that setup path |

Optional permissions can be left disabled when the related feature is not used.

## 한국어

Castla는 광고, 사용 분석, 크래시 리포팅 및 추적 SDK가 없는 오픈소스 앱입니다. 사용자 계정을 만들지 않으며 영상·오디오 스트림을 Castla 클라우드 서비스에 업로드하지 않습니다.

### 기기에서 처리하는 데이터

Castla는 기능 제공을 위해 Android 기기와 연결된 브라우저에서 다음 데이터를 처리합니다.

- Castla 가상 디스플레이의 영상 프레임
- 선택한 앱 UID의 오디오(오디오 스트리밍을 켠 경우)
- 브라우저에서 전송한 터치 및 텍스트 입력
- 런처에 표시할 설치 앱 이름과 아이콘
- 알림 접근 권한을 켠 경우 알림 제목, 본문, 발신자, 앱 이름과 시각
- 로컬 진단 로그

영상, 오디오, 입력 및 알림 payload는 현재 네트워크를 통해 휴대폰과 브라우저 사이에 직접 전송됩니다. 알림 기록은 현재 브라우저 세션의 메모리에 보관됩니다. 진단 로그는 사용자가 직접 공유한 경우에만 휴대폰 밖으로 나갑니다.

### 외부 연결

Castla는 다음 외부 네트워크 요청을 수행할 수 있습니다.

- GitHub API를 통한 새 릴리스 확인
- `car.fbezita.com`을 통한 relay/DNS 등록: `ANDROID_ID`와 선택된 로컬 IP에서 만든 짧은 해시, 생성된 hostname, 로컬 IP 및 relay URL 전송
- 위 서비스 접속에 필요한 일반 DNS 및 HTTPS 요청

relay 등록은 브라우저 접속 주소를 찾을 수 있게 하는 용도이며 영상, 오디오, 터치 또는 알림 스트림을 전달하지 않습니다. 일반적인 HTTPS 요청과 마찬가지로 서비스 운영자와 네트워크 사업자는 접속 공인 IP와 요청 시각 같은 표준 연결 메타데이터를 확인할 수 있습니다.

### 권한 및 특수 접근

| 접근 | 용도 |
|---|---|
| Shizuku | 가상 디스플레이 생성, Task 라우팅, 입력 주입과 display 설정 |
| 마이크/오디오 캡처 | 오디오 스트리밍을 켰을 때 선택한 앱 오디오 캡처 |
| 로컬 네트워크, Wi-Fi, 핫스팟 | 휴대폰과 Tesla 브라우저 연결 |
| Bluetooth | 선택적인 Tesla 근접 및 연결 감지 |
| 설치된 앱 | 가상 디스플레이 런처 구성 |
| 알림 접근 | 선택적인 알림 오버레이와 세션 내 기록 |
| 포그라운드 서비스와 WakeLock | 활성 미러링 세션 유지 |
| 알 수 없는 앱 설치 | 사용자가 해당 설정 경로를 선택했을 때 Shizuku APK 열기 또는 설치 |

관련 기능을 사용하지 않으면 선택 권한은 허용하지 않아도 됩니다.
