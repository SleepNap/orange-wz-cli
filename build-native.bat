@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo  xml-img-patcher native-image build (direct)
echo ============================================================

set "VCDIR=D:\Program Files\Microsoft Visual Studio\2026\Community\VC\Auxiliary\Build"
if not exist "%VCDIR%\vcvarsall.bat" (
    echo [FAIL] vcvarsall.bat not found
    exit /b 1
)
call "%VCDIR%\vcvarsall.bat" x64 > nul
echo [OK] MSVC initialized

set "JAVA_HOME=D:\Program Files\Java\graalvm-jdk-21.0.11+9.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [OK] JAVA_HOME = %JAVA_HOME%

if not exist "%~dp0target\xml-img-patcher.jar" (
    echo [FAIL] target\xml-img-patcher.jar missing. Run mvn package first.
    exit /b 1
)

if not exist "%~dp0dist" mkdir "%~dp0dist"

echo.
echo Running native-image (~5-10 min)...
echo.
"%JAVA_HOME%\bin\native-image" ^
    -jar "%~dp0target\xml-img-patcher.jar" ^
    -H:Name=xml-img-patcher ^
    -H:Path="%~dp0dist" ^
    --no-fallback ^
    -Dfile.encoding=UTF-8 ^
    --initialize-at-build-time=ch.qos.logback,org.slf4j

if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAIL] native-image build failed.
    exit /b 1
)

echo.
echo ============================================================
echo  done.
echo  standalone exe: %~dp0dist\xml-img-patcher.exe
for %%I in ("%~dp0dist\xml-img-patcher.exe") do echo  size: %%~zI bytes
echo ============================================================
endlocal
