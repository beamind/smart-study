# 自动化测试说明

本目录包含 Android 自动化测试用例，包括 UI 测试和功能测试。

## 测试文件结构

```
androidTest/
├── kotlin/com/smartstudy/
│   ├── MainActivityTest.kt                    # 主界面UI测试
│   ├── IntegrationTest.kt                     # 集成测试
│   ├── BaseActivityTest.kt                    # 测试基类
│   ├── ImageUnderstandingServiceInstrumentedTest.kt  # 服务测试
│   ├── dictation/
│   │   ├── chinese/
│   │   │   └── ChineseDictationActivityTest.kt # 中文听写UI测试
│   │   ├── english/
│   │   │   └── EnglishDictationActivityTest.kt # 英文听写UI测试
│   │   └── DictationUseCaseInstrumentedTest.kt # 听写功能测试 ⭐
│   ├── english/
│   │   ├── EnglishErrorBookActivityTest.kt     # 英语错题本UI测试
│   │   └── ErrorBookUseCaseInstrumentedTest.kt # 英语错题本功能测试 ⭐
│   ├── user/
│   │   ├── UserManagementActivityTest.kt       # 用户管理UI测试
│   │   └── UserUseCaseInstrumentedTest.kt      # 用户管理功能测试 ⭐
│   └── utils/
│       └── TestUtils.kt                        # 测试工具类
└── README.md                                   # 本文件
```

⭐ 标记的为新增的功能测试

## 测试覆盖范围

### UI 测试（界面验证）

#### 1. MainActivityTest
- ✅ 主界面所有卡片显示测试
- ✅ 卡片文本内容验证
- ✅ 卡片点击交互测试

#### 2. ChineseDictationActivityTest
- ✅ 中文听写界面按钮显示测试
- ✅ 按钮文本验证
- ✅ 按钮点击交互测试

#### 3. EnglishDictationActivityTest
- ✅ 英文听写界面按钮显示测试
- ✅ 按钮文本验证
- ✅ 按钮点击交互测试

#### 4. UserManagementActivityTest
- ✅ 用户管理界面元素显示测试
- ✅ 按钮交互测试

#### 5. EnglishErrorBookActivityTest
- ✅ 英语错题本界面按钮显示测试
- ✅ 按钮交互测试

#### 6. IntegrationTest
- ✅ 多Activity导航测试
- ✅ 界面间交互流程测试

### 功能测试（业务逻辑验证）⭐

#### 7. DictationUseCaseInstrumentedTest
- ✅ 错题保存和加载功能
- ✅ 错题统计功能
- ✅ 错题列表加载（业务逻辑：过滤和排序）
- ✅ 按错误率选择错题
- ✅ 获取当前用户名
- ✅ 空数据和边界情况处理

#### 8. UserUseCaseInstrumentedTest
- ✅ 用户注册功能（正常流程）
- ✅ 用户注册数据校验（空名称、重复名称）
- ✅ 用户切换功能
- ✅ 用户数据持久化
- ✅ 默认用户管理
- ✅ 获取所有用户列表

#### 9. ErrorBookUseCaseInstrumentedTest
- ✅ 错题加载功能（带统计信息）
- ✅ 错题保存功能（正常流程）
- ✅ 错题保存数据校验（空用户名、空题目等）
- ✅ 错题删除功能
- ✅ 错题更新功能（题目和答案）
- ✅ 错题重新排列功能
- ✅ 数据持久化验证

## 运行测试

### 在 Android Studio 中运行

1. **运行单个测试类**
   - 右键点击测试类文件
   - 选择 "Run 'TestClassName'"

2. **运行单个测试方法**
   - 右键点击测试方法
   - 选择 "Run 'testMethodName'"

3. **运行所有测试**
   - 右键点击 `androidTest` 目录
   - 选择 "Run 'Tests in androidTest'"

### 使用 Gradle 命令行运行

```bash
# 运行所有测试
./gradlew connectedAndroidTest

# 运行特定测试类
./gradlew connectedAndroidTest --tests "com.smartstudy.MainActivityTest"

# 运行特定测试方法
./gradlew connectedAndroidTest --tests "com.smartstudy.MainActivityTest.testMainActivityCardsDisplayed"
```

### 在 Windows 上运行

```cmd
# 运行所有测试
gradlew.bat connectedAndroidTest

# 运行特定测试类
gradlew.bat connectedAndroidTest --tests "com.smartstudy.MainActivityTest"
```

## 测试依赖

测试使用以下依赖库：

- `androidx.test.espresso:espresso-core` - Espresso 核心功能
- `androidx.test.espresso:espresso-intents` - Intent 测试
- `androidx.test.espresso:espresso-contrib` - RecyclerView 等组件测试
- `androidx.test.espresso:espresso-idling-resource` - 异步操作测试
- `androidx.test:rules` - 测试规则
- `androidx.test.ext:junit` - JUnit 扩展

## 注意事项

### 1. 权限问题
- 应用在启动时会自动请求所有必需的权限（相机、麦克风、存储）
- 测试会在 setUp() 中自动处理启动时的权限请求对话框
- 如果权限已被授予，测试将直接继续执行
- 某些测试可能因为权限问题无法完全执行，需要手动授权

### 2. 异步操作
- 涉及网络请求或文件I/O的操作可能需要等待
- 可以使用 `TestUtils.waitFor()` 或 `IdlingResource` 处理

### 3. 测试数据
- **UI测试**：使用真实的应用环境
- **功能测试**：使用独立的测试用户（如"测试用户_xxx"），自动清理测试数据
- 功能测试会在 `@After` 方法中自动清理测试数据，避免污染真实数据
- 建议在测试设备上使用测试数据，避免影响真实数据

### 4. 设备要求
- 需要连接真实的 Android 设备或模拟器
- 设备需要运行 Android 7.0 (API 24) 或更高版本

## 编写新测试

### UI 测试模板

```kotlin
@RunWith(AndroidJUnit4::class)
class YourActivityTest {
    
    @get:Rule
    val activityRule = ActivityScenarioRule(YourActivity::class.java)
    
    @Test
    fun testSomething() {
        onView(withId(R.id.someView))
            .check(matches(isDisplayed()))
    }
}
```

### 功能测试模板

```kotlin
@RunWith(AndroidJUnit4::class)
class YourUseCaseInstrumentedTest {
    
    private lateinit var useCase: YourUseCase
    private lateinit var testUserName: String
    
    @Before
    fun setUp() {
        TestUtils.waitAndHandleStartupPermissions()
        val context = TestUtils.getTestContext()
        testUserName = TestUtils.setupTestUser()
        useCase = YourUseCase(/* dependencies */)
    }
    
    @After
    fun tearDown() {
        TestUtils.clearTestUser(testUserName)
    }
    
    @Test
    fun testSomeFunctionality() = runBlocking {
        // 测试业务逻辑
        val result = useCase.doSomething()
        assertTrue("操作应该成功", result is AppResult.Success)
    }
}
```

### 测试最佳实践

1. **测试命名**：使用描述性的测试方法名，如 `testButtonClickOpensDialog()` 或 `testSaveAndLoadQuestions()`
2. **测试隔离**：每个测试应该是独立的，不依赖其他测试
3. **等待处理**：对于异步操作，使用适当的等待机制（UI测试）或 `runBlocking`（功能测试）
4. **清理资源**：在 `@After` 方法中清理测试资源
5. **测试数据管理**：功能测试使用 `TestUtils.setupTestUser()` 创建测试用户，并在 `@After` 中清理
6. **权限处理**：在 `@Before` 中调用 `TestUtils.waitAndHandleStartupPermissions()` 处理权限

## 故障排除

### 测试失败常见原因

1. **视图未找到**：检查视图ID是否正确，视图是否可见
2. **权限问题**：确保应用已授予必要权限
3. **异步操作**：添加适当的等待时间
4. **设备问题**：确保设备已正确连接且运行正常

### 调试技巧

1. 使用 `adb logcat` 查看日志
2. 在测试中添加 `Thread.sleep()` 观察UI变化
3. 使用 Espresso 的 `onView().perform()` 和 `check()` 进行交互验证

## 持续集成

这些测试可以集成到 CI/CD 流程中：

```yaml
# 示例 GitHub Actions 配置
- name: Run Android Tests
  run: ./gradlew connectedAndroidTest
```

## 测试工具类 (TestUtils)

`TestUtils` 提供了以下辅助方法：

### UI 测试相关
- `waitFor(delayMillis)` - 等待指定时间
- `waitForViewDisplayed(viewId, timeoutMillis)` - 等待视图显示
- `waitAndHandleStartupPermissions()` - 处理启动时的权限请求

### 功能测试相关
- `createTestBitmap(width, height)` - 创建测试用的Bitmap
- `generateTestUserName(prefix)` - 生成唯一的测试用户名
- `setupTestUser(userName)` - 设置测试用户（创建或获取）
- `clearTestUser(userName)` - 清理测试用户及其所有数据
- `clearTestDictationData(userName, dictationType)` - 清理听写数据
- `clearTestErrorBookData(userName)` - 清理错题本数据

## 参考资源

- [Espresso 官方文档](https://developer.android.com/training/testing/espresso)
- [Android 测试指南](https://developer.android.com/training/testing)
- [JUnit 4 文档](https://junit.org/junit4/)
- [Kotlin 协程测试](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-test/)