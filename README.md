# FilesManager

Android ファイルマネージャー（ライトテーマ）。

- ホーム：カテゴリ（ダウンロード／画像／動画／音声／ドキュメント／アプリ／圧縮ファイル／最近）・接続先・コレクション
- NAS：SMB 接続。ログインアカウントは Android Keystore で暗号化して端末内に保存し、次回から自動ログイン
- 動画：アプリ内プレーヤー（Media3 ExoPlayer）。NAS 上の動画も HTTP プロキシ経由でストリーミング再生（Range 対応でシーク可能）
- 更新：このリポジトリの `version.json` を起動時／設定画面で確認し、Releases の APK を DownloadManager でダウンロード（ステータスバーに進捗表示）してインストール
- 設定：ホーム右上／各画面右端の歯車から

## ビルド
Gradle でも、手組み（aapt2 + kotlinc + d8）でもビルドできます。手組みは `build-manual.sh`（依存 AAR/JAR は `manual-deps/` に配置、バイナリはこのリポジトリには含みません）。

- パッケージ：`com.rui.filesmanager`
- minSdk 29（Android 10+）／ targetSdk 36
- Kotlin / Jetpack Compose / Material 3、SMB は smbj、動画は Media3
