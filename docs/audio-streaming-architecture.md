# Castla 오디오 스트리밍 및 라우팅 아키텍처

Last updated: 2026-08-10

이 문서는 현재 구현된 오디오 캡처, 앱별 출력 분리, 코덱 협상, 브라우저 재생 및 A/V 동기화 정책을 설명합니다.

## 1. 동작 모드

### 오디오 스트리밍 비활성화 (`audio_enabled=false`)

- Castla는 시스템 오디오를 캡처하거나 브라우저로 전송하지 않습니다.
- Android가 기존 출력 정책을 그대로 담당합니다.
- Tesla Bluetooth에 연결된 비디오 앱은 별도의 Bluetooth 화면 지연값을 적용할 수 있습니다.
- 내비게이션 음분리 등 휴대폰 자체의 오디오 출력 정책을 변경하지 않습니다.

### 오디오 스트리밍 활성화 (`audio_enabled=true`)

- Shizuku shell UID의 권한으로 UID 범위가 제한된 `AudioPolicy` loopback을 생성합니다.
- 선택된 앱의 PCM을 48 kHz, stereo, 16-bit 형식으로 캡처한 뒤 Opus 또는 PCM으로 브라우저에 전송합니다.
- 대부분의 앱은 브라우저 출력이 기본이며, 내비게이션 앱만 설정에 따라 휴대폰 직접 출력으로 분리합니다.
- 이 모드에서는 Bluetooth 지연값 대신 스트리밍 A/V 오프셋을 사용합니다.

## 2. 앱별 오디오 라우팅

`AudioTargetRegistry`는 실행된 앱을 `(packageName, userId)` 키로 서비스 세션 동안 보존합니다. VD의 현재 앱이 YouTube에서 TMAP으로 바뀌더라도 기존 YouTube UID는 브라우저 캡처 대상에서 사라지지 않습니다.

기본 정책은 다음과 같습니다.

| 앱 유형 | 기본 출력 | AudioPolicy 처리 |
|---|---|---|
| 미디어, 게임 및 일반 앱 | 브라우저 | UID를 `includedUids`에 유지 |
| 내비게이션 앱 | 휴대폰 직접 출력 | 브라우저 캡처 UID에서 제외 |

`내비게이션 오디오를 휴대폰으로 분리` 옵션이 꺼져 있으면 내비게이션도 브라우저로 전송합니다. 옵션이 켜져 있으면 Samsung Separate App Sound 설정을 읽을 수 있는 경우 그 값을 우선합니다. 시스템 값을 읽을 수 없을 때는 Castla의 내비게이션 분류를 fallback으로 사용합니다.

예상 동작은 다음과 같습니다.

1. YouTube 실행: YouTube UID를 브라우저 캡처에 포함합니다.
2. TMAP 실행: YouTube UID는 계속 포함하고 TMAP은 휴대폰 직접 출력으로 둡니다.
3. YouTube 복귀: 실효 캡처 구성이 바뀌지 않으므로 캡처를 재시작하지 않습니다.

캡처 재시작 여부는 `includedUids + routeMode`로 판단합니다. 휴대폰 직접 출력 앱만 추가되어 실효 구성이 같으면 AudioPolicy와 인코더를 유지해 앱 전환 시 폰으로 소리가 순간 누출되거나 팝음이 발생하는 것을 방지합니다.

## 3. 캡처와 코덱 선택

우선 캡처 경로는 Shizuku `REMOTE_SUBMIX` 기반 UID-scoped `AudioPolicy`입니다. Shizuku VD 모드에서는 `MediaProjection`이 없으므로 일반 `AudioPlaybackCapture`는 주 경로가 아니며, UID 제한 AudioPolicy 생성에 실패하면 광범위한 시스템 오디오를 임의로 캡처하지 않고 시작 실패로 처리합니다.

코덱 설정은 두 가지입니다.

- `OPUS_FIRST` (기본): Android Opus encoder와 브라우저 WebCodecs `AudioDecoder`가 모두 사용 가능하면 Opus를 사용합니다.
- `PCM_FIRST`: 압축하지 않은 `pcm_s16le`를 우선 사용합니다.

Opus encoder가 지원되지 않거나 브라우저가 Opus를 디코딩하지 못하면 PCM으로 fallback합니다. Opus 입력은 들어오지만 출력 프레임이 생성되지 않는 경우와 브라우저 디코더 오류도 감지해 새 PCM 스트림을 요청합니다.

## 4. 오디오 전송 프로토콜

오디오는 전용 WebSocket을 사용합니다.

- 설정 패킷: `0x00` 뒤에 JSON을 전송합니다. JSON에는 `streamId`, codec, sample rate, channels, bitrate, frame duration, timestamp base, output delay가 포함됩니다.
- 데이터 패킷: `0x01 + streamId(8) + sequence(4) + timestampUs(8) + payload` 형식입니다.
- `streamId`가 바뀌면 프론트엔드는 이전 세대의 지연 패킷을 폐기합니다.

브라우저는 Opus일 때 WebCodecs `AudioDecoder`, PCM일 때 `AudioBuffer` 변환 경로를 사용합니다. `AudioContext`는 첫 사용자 제스처 이후 시작되며 기본 스케줄링 버퍼는 20ms입니다.

## 5. A/V 동기화 설정

Bluetooth와 스트리밍 오디오는 지연 특성이 다르므로 값을 별도로 저장합니다.

| 설정 | 범위 | 적용 조건 | 의미 |
|---|---:|---|---|
| Tesla Bluetooth 출력 | 0–1000ms | `audio_enabled=false`, Bluetooth 연결, 비디오 앱 | 화면을 지정한 시간만큼 지연 |
| 스트리밍 오디오 A/V 오프셋 | -1000–1000ms | `audio_enabled=true` | 음수는 화면 지연, 양수는 브라우저 오디오 지연 |

스트리밍 오디오 A/V 오프셋의 기본값은 **-30ms**입니다. 즉 기본 상태에서는 영상을 30ms 늦춰 브라우저 오디오와 맞춥니다. 이미 SharedPreferences에 저장된 사용자의 값은 기본값 변경으로 덮어쓰지 않습니다.

양수 오디오 지연은 브라우저의 `DelayNode`에 실시간 반영합니다. 지연 변경 제어는 비디오/레이아웃과 같은 control WebSocket으로 전달하여 오디오 WebSocket의 바이너리 스트림이나 디코더를 재시작하지 않습니다.

## 6. 주요 진단 로그

- `Using UID-scoped REMOTE_SUBMIX`: 실제 포함/제외 UID와 route mode
- `Audio capture started codec=...`: 선택된 codec과 stream generation
- `Audio route refreshed`: 실효 라우팅 변경으로 캡처를 다시 시작함
- `Audio route kept without restart`: 앱 전환 후에도 캡처 구성을 유지함
- `[Audio] First Opus frame decoded`: 브라우저에서 최초 Opus 출력 확인
- `Requesting PCM fallback`: Opus 디코딩 실패 후 PCM 전환 요청

