# 智能学习（Smart Study）

给小学生用的听写和英语错题练习 App：拍照识别词语或题目，App 朗读、计时、记录对错，再按错误率和间隔天数挑出需要复习的内容。同一套代码可以在电脑浏览器里使用和开发，也可以打包成 Android APK。

功能：语文 / 英语听写（拍照、相册或手动输入，朗读两遍，可再读一遍）、错词本和错词听写、英语错题本（识别题目和划线、手动录入、限时练习、举一反三）、学习记录（按次查看和撤销、按日期范围汇总和删除）、多个孩子分别记录。

技术栈：React + TypeScript + Vite，用 Capacitor 打包成 Android App。

规划和约定：

- [软件说明](docs/software-plan.md)：功能、规则、界面原则、技术方案、千问模型、测试
- [数据约定](docs/data-contract.md)：数据文件夹里每个文件的格式
- [架构决定 001：技术选型](docs/decisions/001-web-stack-without-voice-commands.md)
- [架构决定 002：数据放在公共 Documents 文件夹，通过 SAF 插件读写](docs/decisions/002-public-documents-storage-via-saf.md)
- [架构决定 003：release 签名密钥放在仓库外](docs/decisions/003-release-signing.md)
- [给编码助手的工作约定](AGENTS.md)

## 数据与隐私

- 数据保存在 `Documents/smartstudy/data/`，卸载 App 不会丢失；重装后重新选择这个文件夹即可。每个数据文件保留最近的历史版本，误删后可以从 `data/history/` 找回。
- 千问（DashScope）API Key 在 App 的设置页填写，只保存在本机 App 私有存储里，不写入代码、APK 或数据文件夹。每个人用自己的 Key。
- 拍照识别、翻译和举一反三会把照片或文字发送给阿里云 DashScope。没有网络时，已有的词和题照常可以听写、练习和查看。

## 开发

需要 Node.js 22.12+。

```powershell
npm ci
npx playwright install chromium   # 只需一次：下载界面测试用的浏览器
npm run dev        # http://127.0.0.1:5173
```

在电脑上用 Chrome 或 Edge 打开时，可以选择一个本地文件夹作为数据文件夹，例如从平板拷出来的 `smartstudy` 副本，文件格式和手机完全相同。网址参数：`?storage=memory` 只在内存中保存，刷新后消失；`?speech=fake` 不发声；`?ai=fake` 用固定的假结果代替千问，不联网也不花钱。

```powershell
npm run typecheck
npm test               # Vitest：数据校验、存储、统计、抽样、模型输出解析
npx playwright test    # 浏览器里的界面流程（平板和手机两种尺寸，假的朗读和模型）
```

## 测试账号

把某个孩子的数据复制给一个测试账号，在测试账号里随便试，不影响原账号：

```powershell
npx tsx tools/clone-user.ts <smartstudy 文件夹> 乐乐 测试              # 新建"测试"并复制 乐乐 的数据
npx tsx tools/clone-user.ts <smartstudy 文件夹> 乐乐 测试 --replace    # 把"测试"重置为 乐乐 当前的数据
```

源账号的文件只读取不修改。目标账号会设为当前用户，覆盖前的旧版本保存在 `data/history/` 中。

## Android

需要 JDK 21+ 和 Android SDK Platform 36。

```powershell
npm run android:build      # debug 包 com.smartstudy.dev → artifacts/smart-study-<版本>-debug.apk
npm run android:release    # 正式包 com.smartstudy，需要签名密钥 → artifacts/smart-study-<版本>.apk
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_android.ps1 [-Release] -UseChinaMirror -Install [-Serial <设备>]   # 使用国内 Maven 镜像，并用 adb 安装（连着几台设备时用 -Serial 指定）
node tools/smoke_android.mjs emulator-5554   # 模拟器上验证：授权文件夹、读写、卸载重装后数据仍在
```

- 正式包的签名密钥不在仓库里，放法见[架构决定 003](docs/decisions/003-release-signing.md)和 `keystore.properties.example`。自己构建时用自己的密钥即可。
- debug 包和正式包可以同时安装，共用 `Documents/smartstudy` 的数据，但各自要授权文件夹、填写 API Key。
- `smoke_android.mjs` 只接受模拟器序列号，因为它会卸载 App 并替换模拟器上的 `Documents/smartstudy`。它默认自己生成测试数据（两个孩子，约 1,900 个词、500 道题），也可以在第三个参数给一个数据文件夹（至少两个孩子，当前孩子的英语错题本里至少一道题），这时第二个参数写 APK 路径：`node tools/smoke_android.mjs emulator-5554 artifacts/smart-study-<版本>-debug.apk <数据文件夹>`。

## 目录

```text
src/
  app/        启动流程、页面栈与返回键、全局状态（用户、设置、API Key）
  core/       带时区的时间、日期计算
  data/       数据类型、默认值、校验、序列化
  storage/    数据文件夹（SAF / 浏览器文件夹 / 内存）、防写坏的 JSON 读写、内存缓存
  domain/     与界面无关的业务规则（听写、错题本、抽样、统计、划线标记）
  ai/         千问调用、提示词默认值、模型输出解析
  services/   设备能力（朗读、拍照、屏幕常亮、草稿、API Key 存储）
  features/   页面：听写、错词本、错题本、学习记录、用户、设置
  ui/         通用组件
  styles/     tokens.css（颜色、字号、间距）与样式
android/      Capacitor Android 工程；SafFolderPlugin.java 是唯一的自写原生代码
tools/        工具脚本：测试账号、Android 构建与冒烟测试、截图
e2e/          Playwright 测试
docs/         规划、数据约定、架构决定
```
