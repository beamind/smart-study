# 001：用 Web 技术开发，用 Capacitor 打包成 Android App，不做语音指令

日期：2026-10-31。状态：已采用。

## 背景

App 主要在安卓手机和平板上用，但开发、调试和家长查看数据时，在电脑上能直接打开会方便很多：改界面不用每次编译安装，用 Playwright 可以自动走完整个流程，也能用从平板拷出来的数据在电脑上试用。

## 决定

1. **React + TypeScript + Vite，用 Capacitor 8 打包成 Android App**，只做 Android。按功能拆分页面（听写、错题本、学习记录、用户、设置），设备能力放在 `src/services/` 和 `src/storage/` 的接口后面，页面不直接调用原生插件。
2. **不做语音指令**（"开始""下一个""写好了"）。孩子做听写时都是点按钮。不做这个功能，就不需要离线语音识别模型（十几 MB）、麦克风权限，也不用处理"朗读结束后才能开麦"的时序。
3. **原生能力尽量用现成插件**：朗读、相机、屏幕常亮、返回键。只有公共文件夹存储需要自写插件，见[架构决定 002](002-public-documents-storage-via-saf.md)。
4. **模型调用直接 fetch DashScope 的 OpenAI 兼容接口**，不用 SDK。这个接口的跨域预检返回 `access-control-allow-origin: *`，电脑浏览器和 Android WebView 可以共用同一份代码。
5. **API Key 在 App 设置里填写**，保存在 App 私有存储里，不编译进 APK，也不需要后端。仓库可以开源，每个人用自己的 Key。

## 被放弃的方案

- **原生 Android（Kotlin）**：界面只能在设备或模拟器上调试，也不能在电脑上使用。
- **语音指令写成 Capacitor 原生插件**：要维护原生音频线程、模型和 AAR，而这个功能用得很少。
- **用 WebAssembly 版 sherpa-onnx 在 WebView 里做语音识别**：要处理麦克风采集、多线程和性能问题，风险更高。

## 影响

- 测试分两层：Vitest 测业务规则，Playwright 在浏览器里测界面流程；朗读时序、相机和文件夹授权在真机上验证。
- 构建环境：Node.js 22.12+、JDK 21、Android SDK 36。
