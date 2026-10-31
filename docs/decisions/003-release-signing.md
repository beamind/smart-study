# 003：release 签名密钥放在仓库外

日期：2026-10-31。状态：已采用。

## 背景

正式安装用的 release 包（应用 ID `com.smartstudy`）需要签名。Android 只允许用同一把密钥签名的包覆盖安装：密钥丢了，以后的新版本就只能先卸载再安装。数据在 `Documents/smartstudy`，卸载不会丢数据，但每次都要重新授权文件夹、重新填 API Key。

仓库准备开源，密钥和密码不能进 git。

## 决定

- 2026-10-31 生成了一把新密钥：RSA 4096，PKCS12 格式，有效期 100 年，别名 `smartstudy`。证书 SHA-256 指纹是 `84:23:4C:46:8C:C4:7F:F4:E4:09:22:62:0D:79:04:20:DB:DA:C9:7D:50:9D:CD:A0:B3:A9:08:BA:D4:AE:0A:5A`。
- 密钥文件和密码放在 `%USERPROFILE%\.smartstudy\`（`smartstudy-release.jks` 和 `keystore.properties`），不在仓库里。密码是随机生成的，只写在这个文件里。
- `android/app/build.gradle` 读取 `keystore.properties`：如果用 `-PsmartStudyKeystore=<文件>` 或环境变量 `SMART_STUDY_KEYSTORE_PROPERTIES` 指定了文件，就只用这个文件，文件不存在时构建停止；否则依次找仓库根目录（被 git 忽略）和 `%USERPROFILE%\.smartstudy\`。找到的文件缺少 `storeFile`、`storePassword`、`keyAlias`、`keyPassword` 中任何一项时，构建停止。都找不到时 release 包不签名，`npm run android:release` 会报错并停止。格式见 `keystore.properties.example`。
- `.gitignore` 忽略 `*.jks`、`*.keystore`、`*.p12`、`*.pfx`、`keystore.properties` 和 `.env*`。
- `npm run android:release` 构建完成后用 `apksigner` 打印证书，可以和上面的指纹核对。
- debug 包仍用 `com.smartstudy.dev` 和 Android SDK 自带的调试密钥，可以和 release 包同时安装。

## 被放弃的方案

- **把密钥提交到私有仓库或加密后提交**：仓库要开源，提交加密密钥会让密码成为唯一的保护，而且以后很难撤回。
- **Google Play 应用签名**：App 不上架 Google Play，直接安装 APK。

## 影响

- **必须备份** `%USERPROFILE%\.smartstudy\` 整个文件夹（例如拷到 U 盘或密码管理器）。换电脑时把它拷到新电脑的同一位置即可构建。
- release 包关闭了 WebView 调试，`tools/smoke_android.mjs` 和其他用 Playwright 驱动 App 的脚本只能用 debug 包。
- release 包和 debug 包是两个 App：各自需要授权 `Documents/smartstudy`、各自保存 API Key 和未完成的听写草稿；数据文件夹是同一个。
