# Smart Study - Android版

专注于听写练习和错题管理的轻量级 Android 学习应用。

## 功能模块

### 听写练习（语文/英语）

- 拍照识别词语（千问多模态）
- 自动打乱顺序
- 语音朗读（系统TTS，无需网络）
- 语音控制进度（"写好了"、"下一个"）
- 错词记录和复习
- 多用户支持

### 英语错题本

- **拍照识别错题**：拍照识别英语试卷中的题目，自动提取题目和正确答案
- **错题管理**：查看、编辑、删除已保存的错题，支持批量操作
- **错题练习**：基于错误率和练习时间智能选择错题进行练习，支持语音控制进度
- **举一反三**：使用AI生成与原题考察相同知识点但内容不同的新题目，帮助巩固学习
- **练习统计**：记录每道题目的练习次数、错误次数，智能推荐需要复习的题目
- 多用户支持，每个用户的错题记录独立保存

## 配置说明

### 1. 发布签名（Release 构建）

在 `keystore.properties` 或 `%USERPROFILE%\.gradle\gradle.properties` 配置：

```
KEYSTORE_FILE=路径/keystore.jks
KEYSTORE_PASSWORD=密码
KEY_ALIAS=别名
KEY_PASSWORD=密码
```

### 2. 千问 API Key（用于图片理解、翻译和题目生成）

在 `%USERPROFILE%\.gradle\gradle.properties` 添加：

```
DASHSCOPE_API_KEY=你的密钥
```

或构建时传参：

**Windows:**
```bash
.\gradlew.bat :app:assembleDebug -PDASHSCOPE_API_KEY=你的密钥
```

**Linux/Mac:**
```bash
./gradlew :app:assembleDebug -PDASHSCOPE_API_KEY=你的密钥
```

**注意**：
- TTS功能使用系统自带的TextToSpeech引擎，无需API Key和网络连接
- 语音识别使用本地关键词检测（Sherpa-onnx），无需API Key和网络连接
- 图片理解、翻译和"举一反三"题目生成功能需要千问 API Key

### 3. 权限

- **相机**：拍照识别词语和错题
- **麦克风**：语音识别控制听写和练习进度
- **存储**：保存听写记录、错题记录和用户数据（需要授权访问 Documents/smartstudy 文件夹）

## 使用说明

### 听写功能

1. 点击"语文听写"或"英语听写"
2. 拍照识别词语
3. 应用自动打乱顺序并开始朗读
4. 说"写好了"或"下一个"继续下一个词语
5. 查看错词列表进行复习

### 英语错题本

1. **拍照识别错题**：
   - 点击"拍照识别错题"
   - 拍摄英语试卷中的错题
   - 裁剪图片选择题目区域
   - 应用自动识别题目和正确答案
   - 勾选需要保存的题目并提交

2. **查看错题**：
   - 点击"查看错题"查看已保存的所有错题
   - 长按题目进入编辑模式，可修改题目和答案
   - 支持批量删除错题

3. **错题练习**：
   - 点击"错题练习"
   - 输入练习题目数量（支持快捷选择5-40题）
   - 应用根据错误率和练习时间智能选择错题
   - 语音说"开始练习"开始，说"下一个"或"写好了"进入下一题
   - 练习完成后查看答案，勾选仍做错的题目保存

4. **举一反三**：
   - 点击"举一反三"
   - 输入要生成的题目数量
   - 应用从错题中智能选择题目，生成相似的新题目
   - 完成练习后可选择保存新题目到错题本

### 用户管理

- 支持多用户切换
- 每个用户的听写记录和错题记录独立保存
- 可在主界面进入"用户管理"添加新用户

## 技术栈

- Kotlin
- AndroidX、Material Design
- DashScope SDK（千问 API）
- Sherpa-onnx（关键词检测）
- ONNX Runtime
- OkHttp、Gson
- Glide（图片加载）
- Android Image Cropper（图片裁剪）

**要求**：JDK 17+、Android SDK 24-34

## 构建和运行

1. 使用 Android Studio 打开项目（JDK 17+）
2. 配置 API 密钥（见配置说明）
3. 运行或构建 APK

### 构建命令

**Windows:**
```bash
# Debug 版本
.\gradlew.bat :app:assembleDebug

# Release 版本
.\gradlew.bat :app:assembleRelease
```

**Linux/Mac:**
```bash
# Debug 版本
./gradlew :app:assembleDebug

# Release 版本
./gradlew :app:assembleRelease
```

## 运行测试

**Windows:**
```bash
# 所有单元测试
.\gradlew.bat :app:testDebugUnitTest

# 单个测试
.\gradlew.bat :app:testDebugUnitTest --tests "com.smartstudy.ImageUnderstandingServiceTest.testModesExist"
```

**Linux/Mac:**
```bash
# 所有单元测试
./gradlew :app:testDebugUnitTest

# 单个测试
./gradlew :app:testDebugUnitTest --tests "com.smartstudy.ImageUnderstandingServiceTest.testModesExist"
```

## 项目结构

```
app/src/main/kotlin/com/smartstudy/
├── dictation/          # 听写功能模块
│   ├── chinese/       # 中文听写
│   ├── english/       # 英文听写
│   ├── core/          # 核心服务（TTS、语音识别、图片理解等）
│   └── ErrorWordListActivity.kt  # 错词列表
├── english/           # 英语错题本模块
│   ├── EnglishErrorBookActivity.kt  # 错题本主界面
│   ├── EnglishErrorBookStorage.kt  # 错题存储管理
│   ├── QuestionAdapter.kt  # 题目列表适配器
│   └── QuestionGenerationService.kt  # AI题目生成服务
├── storage/           # 存储管理
├── user/              # 用户管理
├── utils/             # 工具类（图片选择、权限管理等）
└── MainActivity.kt    # 主界面
```

## 注意事项

- 图片理解、翻译和题目生成功能需要网络连接（千问 API）
- TTS功能使用系统引擎，无需网络连接
- 语音识别使用本地关键词检测（Sherpa-onnx），无需网络连接
- 注意千问 API 调用频率限制
- 首次使用需要授权存储权限（选择 Documents/smartstudy 文件夹）
- 应用仅支持 arm64-v8a 架构（64位设备）
- 应用版本：1.3