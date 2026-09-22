<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Castla app icon">
  <h1 align="center">Castla</h1>
  <p align="center">Run Android apps on a Tesla display through the built-in browser.</p>
  <p align="center">
    <a href="https://github.com/fbezita/castla/releases/latest"><img src="https://img.shields.io/github/v/release/fbezita/castla?style=flat-square" alt="Latest release"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="Apache 2.0 license"></a>
  </p>
  <p align="center">
    <a href="README.ko.md">한국어</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.de.md">Deutsch</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a>
  </p>
</p>

<p align="center">
  <img src="docs/images/main.png" width="700" alt="Castla mirroring Android apps to a Tesla display">
</p>

Castla creates Shizuku-backed virtual displays on an Android phone and streams them to Tesla's browser. It supports touch input, single/split/popup layouts, audio streaming, notification overlays, and optional hotspot control.

## Requirements

- Android 8.0 or newer
- [Shizuku](https://shizuku.rikka.app/) running with Castla permission
- A Tesla with the built-in browser
- Phone and vehicle connected to the same Wi-Fi network or phone hotspot

## Install and use

1. Download the APK from [Releases](https://github.com/fbezita/castla/releases/latest) and install it.
2. Start Shizuku through wireless debugging and grant Castla permission.
3. Connect the phone and Tesla to the same network.
4. Tap **Start Mirroring** in Castla.
5. Open the address shown by Castla in Tesla's browser.

See the [Shizuku setup guide](shizuku-install-guide.md) and [frontend guide](docs/frontend-user-guide.md) for detailed setup and browser controls.

## Samsung Modes and Routines

Open Castla once after installation so Android can register its app actions. In **Modes and Routines → Open an app or do an app action**, select:

- **Castla → Start server** to start mirroring with the saved settings.
- **Castla → Stop server** to shut down the server, release virtual displays and audio resources, and close the Castla task.

Launching the normal Castla icon only opens the app and does not start the server. If a routine uses **Close an app**, Castla also treats task removal as a graceful server shutdown.

## Build and test

```bash
git clone https://github.com/fbezita/castla.git
cd castla
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK is generated under `app/build/outputs/apk/debug/`.

## Project information

- [Architecture](docs/architecture.md)
- [Contributing](CONTRIBUTING.md)
- [Privacy](PRIVACY.md)
- [Third-party notices](NOTICE)
- [Apache License 2.0](LICENSE)
