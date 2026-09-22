<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Castla 앱 아이콘">
  <h1 align="center">Castla</h1>
  <p align="center">테슬라 내장 브라우저에서 안드로이드 앱을 실행합니다.</p>
  <p align="center">
    <a href="https://github.com/fbezita/castla/releases/latest"><img src="https://img.shields.io/github/v/release/fbezita/castla?style=flat-square" alt="최신 릴리스"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="Apache 2.0 라이선스"></a>
  </p>
  <p align="center">
    <a href="README.md">English</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.de.md">Deutsch</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a>
  </p>
</p>

Castla는 Shizuku 기반 가상 디스플레이를 안드로이드 폰에 만들고 테슬라 브라우저로 전송합니다. 터치 입력, 싱글·분할·팝업 레이아웃, 오디오 스트리밍, 알림 오버레이 및 선택적 핫스팟 제어를 지원합니다.

## 요구 사항

- Android 8.0 이상
- 실행 중이며 Castla 권한이 허용된 [Shizuku](https://shizuku.rikka.app/)
- 내장 브라우저가 있는 테슬라
- 같은 Wi-Fi 또는 폰 핫스팟에 연결된 폰과 차량

## 설치 및 사용

1. [Releases](https://github.com/fbezita/castla/releases/latest)에서 APK를 내려받아 설치합니다.
2. 무선 디버깅으로 Shizuku를 시작하고 Castla 권한을 허용합니다.
3. 폰과 테슬라를 같은 네트워크에 연결합니다.
4. Castla에서 **미러링 시작**을 누릅니다.
5. Castla가 표시하는 주소를 테슬라 브라우저에서 엽니다.

자세한 설정과 브라우저 조작법은 [Shizuku 설치 가이드](shizuku-install-guide.md)와 [프런트엔드 사용 가이드](docs/frontend-user-guide.md)를 참고하세요.

## 삼성 모드 및 루틴

설치 후 Castla를 한 번 열어 Android에 앱 동작을 등록합니다. **모드 및 루틴 → 앱을 열거나 앱 동작 바로 실행**에서 다음 동작을 선택합니다.

- **Castla → 서버 시작**: 저장된 설정으로 미러링 서버를 시작합니다.
- **Castla → 서버 종료**: 서버를 정상 종료하고 가상 디스플레이와 오디오 자원을 정리한 뒤 Castla 태스크를 닫습니다.

일반 Castla 아이콘을 실행하면 앱만 열리고 서버는 자동으로 시작되지 않습니다. 루틴에서 **앱 종료**를 사용하는 경우에도 Castla는 태스크 제거를 감지해 서버를 정상 종료합니다.

## 빌드 및 테스트

```bash
git clone https://github.com/fbezita/castla.git
cd castla
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

디버그 APK는 `app/build/outputs/apk/debug/` 아래에 생성됩니다.

## 프로젝트 문서

- [아키텍처](docs/architecture.md)
- [기여 가이드](CONTRIBUTING.md)
- [개인정보 처리방침](PRIVACY.md)
- [서드파티 고지](NOTICE)
- [Apache License 2.0](LICENSE)
