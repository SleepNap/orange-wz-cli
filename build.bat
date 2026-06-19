@echo off
rem Build xml-img-patcher fat jar.
rem Output: target\xml-img-patcher.jar
setlocal
pushd %~dp0
call mvn -DskipTests package
set rc=%ERRORLEVEL%
popd
if %rc% neq 0 (
    echo.
    echo [build] failed, exit code %rc%
    exit /b %rc%
)
echo.
echo [build] ok: %~dp0target\xml-img-patcher.jar
endlocal
