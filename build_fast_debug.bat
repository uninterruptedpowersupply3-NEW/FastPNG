@echo off
setlocal enableextensions

set "ROOT=%~dp0"
cd /d "%ROOT%"

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=C:\Users\chatr\AppData\Local\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_USER_HOME=%ROOT%.gradle-user-home"
set "ANDROID_USER_HOME=%ROOT%.android-user-home"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"

set "MAX_WORKERS=%NUMBER_OF_PROCESSORS%"
if "%MAX_WORKERS%"=="" set "MAX_WORKERS=12"

set "GRADLE_OPTS=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 -Dorg.gradle.daemon=true -Dorg.gradle.parallel=true -Dorg.gradle.caching=true -Dorg.gradle.configuration-cache=true -Dkotlin.incremental=true"

echo.
echo Fast debug build for this machine:
echo   CPU threads : %MAX_WORKERS%
echo   Java        : %JAVA_HOME%
echo   Android SDK : %ANDROID_HOME%
echo   Gradle home : %GRADLE_USER_HOME%
echo.
echo Note: the RTX 3070 Ti does not materially accelerate Gradle or Android compilation.
echo This script is tuned for CPU, RAM, NVMe cache reuse, and parallel Gradle workers.
echo.

if not exist "%ROOT%gradlew.bat" (
    echo gradlew.bat was not found in %ROOT%
    exit /b 1
)

start "FastPNGTOWEBP Android Build" /high /wait cmd /c ^
    call "%ROOT%gradlew.bat" assembleDebug -x lint -x test --parallel --build-cache --configuration-cache --max-workers=%MAX_WORKERS%

set "EXITCODE=%ERRORLEVEL%"
echo.
if not "%EXITCODE%"=="0" (
    echo Build failed with exit code %EXITCODE%.
    exit /b %EXITCODE%
)

echo Build succeeded.
echo APK: %ROOT%app\build\outputs\apk\debug\app-debug.apk
exit /b 0
