# 给编码助手的工作约定

开始前先读 `docs/software-plan.md`（功能、规则和待完成的事）和 `docs/data-contract.md`（数据格式）。

## 必须遵守

- **数据安全优先**。所有数据文件都通过 `src/storage/jsonFiles.ts` 的 `JsonFiles` 读写，不要绕过它直接写文件。读到版本更新或格式不认识的文件时，不得覆盖它；读到损坏的文件时，只能在另存一份（`.broken`）之后再替换。一次用户操作只写一次文件。
- **改数据格式要同步**：修改 `src/data/schema.ts`、`src/data/validate.ts` 和 `docs/data-contract.md`，并补测试。不兼容的改动要升级 `schema` 版本号，并让 App 能把上一版的文件转换成新版。
- **API Key 只经过 `src/services/secrets.ts`**，不得写进数据文件夹、日志、代码或测试数据。
- **真实的孩子数据只放在 `.local/`**（已被 git 忽略），测试用自己构造的数据。
- **自写的原生代码只有 `android/app/src/main/java/com/smartstudy/SafFolderPlugin.java`**（`MainActivity.java` 只负责注册它）。新增原生能力优先用现成的 Capacitor 插件，需要自写时先在 `docs/decisions/` 记录理由。
- 页面组件不直接调用 Capacitor 插件；设备能力放在 `src/services/` 或 `src/storage/` 后面，并提供浏览器和测试用实现。
- 界面遵循规划中的"界面原则"：一个页面一个主要动作，孩子用的按钮至少 64px 高（`--tap-child`），颜色、字号、间距只在 `src/styles/tokens.css` 中定义。
- 在真机上测试只用"测试"这类测试账号，不改动孩子的真实账号；测试前后核对真实账号的文件没有变化。

## 提交前检查

```powershell
npm run typecheck
npm test
npx playwright test
```

改动 Android 或存储相关代码时，再运行 `npm run android:build` 和 `node tools/smoke_android.mjs emulator-5554`。改动签名或 Gradle 配置时，再运行 `npm run android:release`，核对打印的证书指纹与 `docs/decisions/003-release-signing.md` 一致。签名密钥和密码不得进入仓库。

架构上的重要取舍记录在 `docs/decisions/NNN-标题.md`，格式参考已有文件。
