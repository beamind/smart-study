# 试用中转服务部署

没有 API Key 的设备经这个服务试用千问。为什么这样做、额度怎么定，见[架构决定 004](decisions/004-trial-proxy.md)。代码在 `server/trial-proxy/`。

整体是：阿里云函数计算（中国香港）上的一个 Web 函数，用一个只用于试用的千问 Key 调用北京的千问，计数存在同一地域的表格存储（Tablestore）里。

控制台的菜单名称时常调整，下面写的是 2026-10 的名称，找不到时按意思找相近的选项。

## 1. 千问：只用于试用的 Key

1. 打开[百炼控制台](https://bailian.console.aliyun.com/)，右上角地域选“华北2（北京）”。
2. 在“业务空间管理”里新建一个业务空间，例如“智能学习试用”。
3. 在这个业务空间的模型授权里，只允许 `qwen3.8-flash` 和 `qwen3.8-max`。
4. 切换到这个业务空间，在“密钥管理”里创建 API Key。复制下来，第 5 步要用。

这个 Key 不要用在别处，也不要放进仓库、聊天记录或截图里。账单里可以按业务空间看试用花了多少。

## 2. 表格存储：计数表

1. 打开[表格存储控制台](https://otsnext.console.aliyun.com/)，地域选“中国香港”。
2. 创建实例：按量计费，实例名例如 `smartstudy-trial`。
3. 在实例的“网络管理”里确认允许公网访问（函数不放进专有网络 VPC，走公网访问表格存储）。
4. 在实例里创建数据表：
   - 表名 `trial_counters`；
   - 主键只有一个：`id`，类型“字符串”；
   - 数据生命周期 `-1`（永不过期：每台设备的试用次数要一直记着），最大版本数 `1`。
5. 记下实例详情页的“公网访问地址”，形如 `https://smartstudy-trial.cn-hongkong.ots.aliyuncs.com`。

表里每行是一个计数：`device:<设备标识>:<photo|translate|generate>` 是这台设备用过的次数；`ip:<日期>:<哈希>:<basic|generate>` 是一个 IP 当天的次数；`cost:<日期>:<basic|generate>` 是当天的千问花费，单位是百万分之一元。

## 3. 权限：函数的角色

函数用角色访问表格存储，不需要 AccessKey。

1. 在 [RAM 控制台](https://ram.console.aliyun.com/)创建权限策略，例如 `smartstudy-trial-ots`，内容（把实例名换成你的）：

   ```json
   {
     "Version": "1",
     "Statement": [{
       "Effect": "Allow",
       "Action": ["ots:GetRow", "ots:PutRow", "ots:UpdateRow"],
       "Resource": "acs:ots:cn-hongkong:*:instance/smartstudy-trial/table/trial_counters"
     }]
   }
   ```

2. 创建角色：可信实体选“阿里云服务”，服务选“函数计算”。给它加上这条策略。

## 4. 打包代码

```powershell
npm ci
npm run trial:build
Compress-Archive -Path server/trial-proxy/dist/index.cjs -DestinationPath server/trial-proxy/dist/code.zip -Force
```

`index.cjs` 是一个打包好的文件，包含所有依赖，不需要再上传 `node_modules`。提示词和模型来自 `src/ai/tasks.ts` 和 `src/data/defaults.ts`，改了它们要重新打包上传。

## 5. 创建函数

在[函数计算控制台](https://fcnext.console.aliyun.com/)，地域选“中国香港”，创建函数，类型选“Web 函数”：

| 配置 | 值 |
|---|---|
| 运行环境 | 自定义运行时，Node.js 20 |
| 代码 | 上传第 4 步的 `code.zip` |
| 启动命令 | `node index.cjs` |
| 监听端口 | `9000` |
| 规格 | 0.35 vCPU，512 MB |
| 执行超时 | 60 秒（服务端等千问最多 40 秒） |
| 单实例并发度 | 10 |
| 实例数上限 | 2（被刷时也不会开很多实例） |
| 函数角色 | 第 3 步的角色 |
| 网络 | 不使用专有网络，允许访问公网 |

环境变量：

| 名称 | 值 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 第 1 步的 Key | 必填 |
| `TABLESTORE_ENDPOINT` | 第 2 步的公网访问地址 | 必填 |
| `TABLESTORE_INSTANCE` | 实例名，例如 `smartstudy-trial` | 必填 |
| `TABLESTORE_TABLE` | `trial_counters` | 不填就是这个 |
| `IP_SALT` | 一串随机字符，见下面 | 必填，不要公开 |
| `DEVICE_PHOTO`、`DEVICE_TRANSLATE`、`DEVICE_GENERATE` | 每台设备总共的次数 | 不填为 20、100、10 |
| `IP_BASIC`、`IP_GENERATE` | 每个 IP 每天的次数 | 不填为 60、10 |
| `BUDGET_BASIC_YUAN`、`BUDGET_GENERATE_YUAN` | 每天的千问费用上限（元） | 不填为 0.7、0.3 |
| `PRICE_FLASH`、`PRICE_MAX` | 每百万 Token 的输入、输出单价（元） | 不填为 `0.8,2.7`、`12,36`。千问调价后要改 |

生成 `IP_SALT`：

```powershell
node -e "console.log(require('crypto').randomBytes(16).toString('hex'))"
```

不要设置 `TRIAL_STORE`，它只用于在电脑上试（计数存在内存里，重启就没了）。

## 6. HTTP 触发器

给函数加 HTTP 触发器：认证方式“无需认证”，请求方法 `GET`、`POST`、`OPTIONS`。保存后，触发器页会显示公网访问地址，形如 `https://<一串字符>.cn-hongkong.fcapp.run`。

这个自带地址官方说明只用于测试。以后有了自己的域名，绑定到这个函数，再按第 8 步改地址、发新版本即可。

## 7. 检查

```powershell
node tools/check_trial.mjs https://<触发器地址>/
```

它会查询剩余次数、翻译 3 个词、检查跨域设置和拒绝不支持的请求，全部“通过”才算部署好。花费不到 1 分钱。

然后检查 IP 是怎么取到的。服务把 `X-Forwarded-For` 里最后一个地址当作用户的 IP；如果网关不加这个头，或者不加在最后，伪造的地址就能绕过每 IP 限制。脚本最后两次翻译中，有一次在请求头里伪造了一个地址。到表格存储里看 `trial_counters` 表：

- `device:<脚本打印的设备标识>:translate` 应为 3；
- `ip:<今天>:…:basic` 的行里，应该只有一行比运行前多了 3。如果是两行分别多了 2 和 1，说明 IP 取错了，先不要发布，告诉开发者。

最后在函数的日志里能看到每次调用一行，例如 `{"task":"translate","device":"check-87","input":60,"output":3,"cost":57}`。

## 8. 写进 App 并发布

把 `src/ai/trial.ts` 里的 `RELEASE_TRIAL_URL` 改成触发器地址，提交：

```ts
const RELEASE_TRIAL_URL = 'https://<触发器地址>/';
```

这个地址会出现在 APK 里，本来就是公开的。只有构建 APK（`npm run android:build`、`npm run android:release`）和 `npm run build` 用它；`npm run dev`、单元测试和 Playwright 不用，平时开发不会用掉试用额度。自己构建时不想带试用，构建前设置 `$env:VITE_TRIAL_URL = 'off'`。

在模拟器上装好，不填 Key，点“先免费试用”，拍一张照片识别；设置页的“试用还剩”应该减少一次。不要为此清掉真实设备上已经填好的 Key。

## 9. 平时

- **看花费**：百炼按业务空间看千问的账单；函数计算和表格存储在费用中心看。
- **改额度**：改函数的环境变量，保存后生效。
- **暂停试用**：把 `BUDGET_BASIC_YUAN` 和 `BUDGET_GENERATE_YUAN` 改成 `0`，App 会提示“今天的试用名额用完了”；或者停用 HTTP 触发器，App 会提示网络出错。填了自己 Key 的人不受影响。
- **Key 泄露或怀疑被滥用**：在百炼里删掉这个 Key，新建一个，改 `DASHSCOPE_API_KEY`。
- **更新代码**：重新做第 4 步，在函数的代码页上传新的 `code.zip`，再做第 7 步。
