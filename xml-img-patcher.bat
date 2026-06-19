@echo off
rem Launcher wrapper: after putting this dir on PATH, run
rem   xml-img-patcher patch a.img a.diff b.img
rem JAR sits next to this script under target\; change JAR if you move it.
setlocal
set JAR=%~dp0target\xml-img-patcher.jar
if not exist "%JAR%" (
    echo [xml-img-patcher] jar not found: %JAR%
    echo                   run build.bat in the project root first
    exit /b 2
)
java -jar "%JAR%" %*
endlocal
