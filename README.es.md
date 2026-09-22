<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Icono de Castla">
  <h1 align="center">Castla</h1>
  <p align="center">Ejecuta aplicaciones Android en el navegador integrado de Tesla.</p>
  <p align="center">
    <a href="README.md">English</a> · <a href="README.ko.md">한국어</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.de.md">Deutsch</a> · <a href="README.fr.md">Français</a>
  </p>
</p>

Castla usa Shizuku para crear pantallas virtuales independientes en un teléfono Android y transmitirlas al navegador de Tesla. Incluye control táctil, diseños individual, dividido y emergente, audio, superposición de notificaciones y control opcional del punto de acceso.

## Requisitos

- Android 8.0 o posterior
- [Shizuku](https://shizuku.rikka.app/) en ejecución y autorizado para Castla
- Tesla con navegador integrado
- Teléfono y vehículo en la misma red Wi-Fi o punto de acceso del teléfono

## Instalación y uso

1. Descarga e instala el APK desde [Releases](https://github.com/fbezita/castla/releases/latest).
2. Inicia Shizuku mediante depuración inalámbrica y autoriza Castla.
3. Conecta el teléfono y el Tesla a la misma red.
4. Inicia el servidor de mirroring en Castla.
5. Abre en el navegador de Tesla la dirección mostrada por Castla.

Consulta la [guía de Shizuku](shizuku-install-guide.md) y la [guía del navegador](docs/frontend-user-guide.md) para más detalles.

## Información del proyecto

- [Arquitectura](docs/architecture.md)
- [Contribuir](CONTRIBUTING.md)
- [Privacidad](PRIVACY.md)
- [Avisos de terceros](NOTICE)
- [Apache License 2.0](LICENSE)
