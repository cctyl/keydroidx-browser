@echo off
setlocal

REM ============================================================
REM  Build debug APK.
REM
REM  Variant: lightningPlus (default) -> app\build\outputs\apk\lightningPlus\debug\
REM           for the Lite flavor change the two lines below to
REM           :app:assembleLightningLiteDebug and ...\lightningLite\debug
REM
REM  Usage: build_debug.bat
REM ============================================================

set "ROOT=%~dp0"
set "TASK=:app:assembleLightningPlusDebug"

echo Building debug APK (%TASK%) ...
call "%ROOT%gradlew.bat" %TASK%
if errorlevel 1 (
    echo [ERROR] Build failed. See log above.
    pause
    exit /b 1
)

echo Done. APK at: app\build\outputs\apk\lightningPlus\debug\
endlocal
pause
