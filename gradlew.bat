@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal EnableExtensions EnableDelayedExpansion

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:execute
@rem ---------------------------------------------------------------------------
@rem Preflight: AGP 8.x requires running Gradle with JDK 17+.
@rem This check must happen BEFORE Gradle tries to resolve the Android Gradle Plugin,
@rem otherwise you'll see variant-matching errors like "compatible with Java 8".
@rem ---------------------------------------------------------------------------
set JAVA_VERSION_STR=
set JAVA_VER_TMP=
set "JAVA_VER_TMP=%TEMP%\gradle-java-version-%RANDOM%.txt"
@rem Write java -version output to a temp file (stderr->stdout), to avoid cmd quoting edge-cases with pipes.
"%JAVA_EXE%" -version > "!JAVA_VER_TMP!" 2>&1
for /f "tokens=1,2,3" %%a in ('findstr /i "version" "!JAVA_VER_TMP!"') do (
  if /i "%%b"=="version" set JAVA_VERSION_STR=%%c
)
del /q "!JAVA_VER_TMP!" >NUL 2>&1
set JAVA_VERSION_STR=!JAVA_VERSION_STR:"=!
set JAVA_MAJOR=
for /f "delims=." %%m in ("!JAVA_VERSION_STR!") do set JAVA_MAJOR=%%m
if "!JAVA_MAJOR!"=="1" (
  for /f "tokens=2 delims=." %%n in ("!JAVA_VERSION_STR!") do set JAVA_MAJOR=%%n
)
if "!JAVA_MAJOR!"=="" goto executeGradle
if !JAVA_MAJOR! LSS 17 (
  echo.
  echo ERROR: This project requires JDK 17+ to run Gradle/Android Gradle Plugin (AGP 8.x^).
  echo Detected Java version: !JAVA_VERSION_STR!
  echo.
  echo Fix:
  echo   - Android Studio: Settings/Preferences ^> Build Tools ^> Gradle ^> Gradle JDK = JDK 17+
  echo   - Command line: set JAVA_HOME to a JDK 17+ installation, then re-run gradlew.
  echo.
  goto fail
)

:executeGradle
@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar


@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
