@echo off
REM Smart Study 编译验证脚本
REM JDK路径: C:\Program Files\Android\Android Studio\jbr

setlocal

REM 设置JDK路径
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr

echo ========================================
echo Smart Study 编译验证
echo ========================================
echo.

echo [1/6] 清理项目...
call gradlew.bat clean --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo 清理失败！
    exit /b 1
)
echo 清理完成
echo.

echo [2/6] 编译Debug版本...
call gradlew.bat :app:assembleDebug --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo Debug版本编译失败！
    exit /b 1
)
echo Debug版本编译成功
echo.

echo [3/6] 编译Release版本...
call gradlew.bat :app:assembleRelease --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo Release版本编译失败！
    exit /b 1
)
echo Release版本编译成功
echo.

echo [4/6] 运行ktlint检查...
call gradlew.bat ktlintCheck --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo ktlint检查失败！
    exit /b 1
)
echo ktlint检查通过
echo.

echo [5/6] 运行detekt检查...
call gradlew.bat detekt --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo detekt检查失败！
    exit /b 1
)
echo detekt检查通过
echo.

echo [6/6] 运行单元测试...
call gradlew.bat :app:testDebugUnitTest --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo 单元测试失败！
    exit /b 1
)
echo 单元测试通过
echo.

echo ========================================
echo 所有验证通过！
echo ========================================
endlocal
