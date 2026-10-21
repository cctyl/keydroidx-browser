@echo off
setlocal

REM ============================================================
REM  Build debug APK.
REM
REM  Output: app\build\outputs\apk\debug\
REM
REM  Usage: build_debug.bat
REM ============================================================

set "ROOT=%~dp0"
set "TASK=:app:assembleDebug"

echo Building debug APK (%TASK%) ...
call "%ROOT%gradlew.bat" %TASK%
if errorlevel 1 (
    echo [ERROR] Build failed. See log above.
    pause
    exit /b 1
)

echo Done. APK at: app\build\outputs\apk\debug\
endlocal
pause
