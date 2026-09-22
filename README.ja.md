<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Castlaアプリアイコン">
  <h1 align="center">Castla</h1>
  <p align="center">AndroidアプリをTeslaの内蔵ブラウザで実行します。</p>
  <p align="center">
    <a href="README.md">English</a> · <a href="README.ko.md">한국어</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.de.md">Deutsch</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a>
  </p>
</p>

Castlaは、Shizukuを使ってAndroidスマートフォン上に独立した仮想ディスプレイを作成し、Teslaブラウザへストリーミングします。タッチ操作、単一・分割・ポップアップ表示、音声ストリーミング、通知オーバーレイ、オプションのホットスポット制御に対応します。

## 必要条件

- Android 8.0以降
- 起動中で、Castlaへの権限が許可された[Shizuku](https://shizuku.rikka.app/)
- 内蔵ブラウザを搭載したTesla
- 同じWi-Fiまたはスマートフォンのホットスポットに接続されたスマートフォンと車両

## インストールと使用

1. [Releases](https://github.com/fbezita/castla/releases/latest)からAPKをインストールします。
2. ワイヤレスデバッグでShizukuを起動し、Castlaへのアクセスを許可します。
3. スマートフォンとTeslaを同じネットワークに接続します。
4. Castlaでミラーリングサーバーを開始します。
5. Castlaに表示されたアドレスをTeslaブラウザで開きます。

詳しくは[Shizuku設定ガイド](shizuku-install-guide.md)と[ブラウザ使用ガイド](docs/frontend-user-guide.md)を参照してください。

## プロジェクト情報

- [アーキテクチャ](docs/architecture.md)
- [コントリビューション](CONTRIBUTING.md)
- [プライバシー](PRIVACY.md)
- [第三者ライセンス表記](NOTICE)
- [Apache License 2.0](LICENSE)
