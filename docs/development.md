# 开发说明

智能学习用 React + TypeScript + Vite 写成，用 Capacitor 打包成 Android App。同一套代码也能在电脑的 Chrome 或 Edge 里直接运行，方便开发和调试。

先读这些：

- [软件说明](software-plan.md)：功能、规则、界面原则、技术方案、千问模型、测试
- [数据约定](data-contract.md)：数据文件夹里每个文件的格式
- [架构决定 001：技术选型](decisions/001-web-stack-without-voice-commands.md)
- [架构决定 002：数据放在公共 Documents 文件夹，通过 SAF 插件读写](decisions/002-public-documents-storage-via-saf.md)
- [架构决定 003：release 签名密钥放在仓库外](decisions/003-release-signing.md)
- [架构决定 004：没有 API Key 时，通过云函数中转试用](decisions/004-trial-proxy.md)
- [给编码助手的工作约定](../AGENTS.md)：也是给所有贡献者的约定，例如数据安全、界面规则和提交前检查

## 在电脑上运行

需要 Node.js 22.12+。

```powershell
npm ci
npx playwright install chromium   # 只需一次：下载界面测试用的浏览器
npm run dev        # http://127.0.0.1:5173
```

在 Chrome 或 Edge 里打开时，可以选择一个本地文件夹作为数据文件夹，例如从平板拷出来的 `smartstudy` 副本，文件格式和手机完全相同。网址参数：

- `?storage=memory`：只在内存中保存，刷新后消失；
- `?speech=fake`：不发声；
- `?ai=fake`：用固定的假结果代替千问，不联网也不花钱。

## 测试

```powershell
npm run typecheck
npm test               # Vitest：数据校验、存储、统计、抽样、模型输出解析、试用中转服务
npx playwright test    # 浏览器里的界面流程（平板和手机两种尺寸，假的朗读和模型）
```

## 试用中转服务

没有 API Key 的设备经 `server/trial-proxy/` 里的中转服务试用千问，原因和额度见[架构决定 004](decisions/004-trial-proxy.md)，部署步骤见[试用中转服务部署](trial-proxy.md)。

- 构建 APK 和 `npm run build` 时用 `src/ai/trial.ts` 里的 `RELEASE_TRIAL_URL`；`npm run dev`、单元测试和 Playwright 不用试用，没有 Key 就不能用千问。环境变量 `VITE_TRIAL_URL` 可以覆盖它，设为 `off` 时不带试用。
- 服务端和 App 共用 `src/ai/tasks.ts` 里的 `modelRequest`（模型和提示词）和 `src/ai/dashscope.ts`；改提示词会同时影响两边，部署前要重新打包上传。
- 在电脑上试：

  ```powershell
  npm run trial:build    # 打包成一个文件 server/trial-proxy/dist/index.cjs
  $env:DASHSCOPE_API_KEY = '<只用于试用的 Key>'; $env:TRIAL_STORE = 'memory'; node server/trial-proxy/dist/index.cjs   # 端口 9000，计数存在内存里
  $env:VITE_TRIAL_URL = 'http://127.0.0.1:9000/'; npm run dev    # 另开一个终端，不填 Key 的 App 就会走本机的中转服务
  ```

## 测试账号

把某个孩子的数据复制给一个测试账号，在测试账号里随便试，不影响原账号：

```powershell
npx tsx tools/clone-user.ts <smartstudy 文件夹> 乐乐 测试              # 新建"测试"并复制 乐乐 的数据
npx tsx tools/clone-user.ts <smartstudy 文件夹> 乐乐 测试 --replace    # 把"测试"重置为 乐乐 当前的数据
```

源账号的文件只读取不修改。目标账号会设为当前用户，覆盖前的旧版本保存在 `data/history/` 中。

## 示例孩子

示例孩子“小华”的数据在 `src/demo/`：三个和数据文件格式相同的 JSON，加上把它们添加为新用户的 `demo.ts`。添加时所有时间按整天往后挪，最近一次记录落在前一天。改这些文件后运行 `npm test`，`src/demo/demo.test.ts` 会检查它们是否通过数据校验。

## Android

需要 JDK 21+ 和 Android SDK Platform 36。

```powershell
npm run android:build      # debug 包 com.smartstudy.dev → artifacts/smart-study-<版本>-debug.apk
npm run android:release    # 正式包 com.smartstudy，需要签名密钥 → artifacts/smart-study-<版本>.apk
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_android.ps1 [-Release] -UseChinaMirror -Install [-Serial <设备>]   # 使用国内 Maven 镜像，并用 adb 安装（连着几台设备时用 -Serial 指定）
node tools/smoke_android.mjs emulator-5554   # 模拟器上验证：授权文件夹、读写、卸载重装后数据仍在
```

- 正式包的签名密钥不在仓库里，放法见[架构决定 003](decisions/003-release-signing.md)和 `keystore.properties.example`。自己构建时用自己的密钥即可；构建脚本会把证书指纹和架构决定 003 里记录的比对，用别的密钥时只会提示不一致。
- debug 包和正式包可以同时安装，共用 `Documents/smartstudy` 的数据，但各自要授权文件夹、填写 API Key。
- `smoke_android.mjs` 只接受模拟器序列号，因为它会卸载 App 并替换模拟器上的 `Documents/smartstudy`。它默认自己生成测试数据（两个孩子，约 1,900 个词、500 道题）。也可以在第三个参数给一个数据文件夹（至少两个孩子，当前孩子的英语错题本里至少一道题），这时第二个参数写 APK 路径：`node tools/smoke_android.mjs emulator-5554 artifacts/smart-study-<版本>-debug.apk <数据文件夹>`。

## 目录

```text
src/
  app/        启动流程、页面栈与返回键、全局状态（用户、设置、API Key）
  core/       带时区的时间、日期计算
  data/       数据类型、默认值、校验、序列化
  storage/    数据文件夹（SAF / 浏览器文件夹 / 内存）、防写坏的 JSON 读写、内存缓存
  domain/     与界面无关的业务规则（听写、错题本、抽样、统计、划线标记）
  ai/         千问调用、试用中转的客户端、提示词默认值、模型输出解析
  demo/       示例孩子小华的数据
  services/   设备能力（朗读、拍照、屏幕常亮、草稿、API Key 和试用设备标识的存储）
  features/   页面：听写、错词本、错题本、学习记录、用户、设置
  ui/         通用组件
  styles/     tokens.css（颜色、字号、间距）与样式
android/      Capacitor Android 工程；SafFolderPlugin.java 是唯一的自写原生代码
server/       试用中转服务（阿里云函数计算），见架构决定 004
tools/        工具脚本：测试账号、Android 构建与冒烟测试、截图
e2e/          Playwright 测试
docs/         规划、数据约定、架构决定、开发说明
```
