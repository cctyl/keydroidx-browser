@echo off
setlocal

REM ============================================================
REM  Build release APK with lint INCLUDED.
REM
REM  minSdk=24: any API call newer than minSdk only fails at
REM  RUNTIME on old devices (NoSuchMethodError /
REM  NoClassDefFoundError). The compiler cannot catch that, so
REM  Lint's NewApi rule is the only static guard.
REM  - NEVER add `-x lint` to this command.
REM  - NEVER add disable("NewApi") to app/build.gradle.kts.
REM    (assembleRelease only runs the fatal lintVital checks, so
REM     :app:lint<Variant> is invoked explicitly here. For a real
REM     false positive, suppress it AT THE CALL SITE with
REM     @SuppressLint / @RequiresApi and explain why.)
REM
REM  NOTE: this project has two flavors, so the plain task names
REM  `lintRelease` / `assembleRelease` are AMBIGUOUS (Gradle fails
REM  with "task ... is ambiguous"). Variant-specific names are
REM  required.
REM  Default is lightningPlus; for the Lite flavor replace the two
REM  lines below with
REM    :app:lintLightningLiteRelease :app:assembleLightningLiteRelease
REM    app\build\outputs\apk\lightningLite\release
REM
REM  Output: app\build\outputs\apk\lightningPlus\release\
REM
REM  Usage: build_release.bat
REM
REM  KEEP THIS FILE ASCII-ONLY: cmd.exe reads .bat bytes using the
REM  OEM code page (936 on zh-CN Windows), so UTF-8 Chinese
REM  comments get mis-decoded and the tail of the line is run as a
REM  command. Put any Chinese explanation in CODEBUDDY.md instead.
REM ============================================================

set "ROOT=%~dp0"
set "OUTDIR=%ROOT%app\build\outputs\apk\lightningPlus\release"

echo [1/2] Lint (NewApi must pass) + build release APK (may take a while) ...
call "%ROOT%gradlew.bat" :app:lintLightningPlusRelease :app:assembleLightningPlusRelease
if errorlevel 1 (
    echo [ERROR] Lint or build failed. See log above.
    pause
    exit /b 1
)

echo [2/2] Opening output folder:
echo   %OUTDIR%
start "" "%OUTDIR%"

echo Done.
endlocal
pause
