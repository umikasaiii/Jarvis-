@echo off
rem BETA 1/8 - raw, app-independent copy of ALL JARVIS app-private data to this PC (Windows cmd.exe, NOT PowerShell:
rem PowerShell 5.1 corrupts binary redirection). Read-only on the phone (adb run-as on the debuggable build).
rem   raw_backup.cmd [package]      default package: com.simone.jarvismobile.debug
setlocal
set PKG=%1
if "%PKG%"=="" set PKG=com.simone.jarvismobile.debug
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set STAMP=%%i
set OUT=jarvis-raw-%STAMP%.tar
adb get-state >nul || exit /b 1
adb shell "run-as %PKG% ls files databases" >nul || (echo run-as failed: wrong package id or not a debug build & exit /b 1)
adb exec-out "run-as %PKG% tar -cf - databases files shared_prefs 2>/dev/null" > %OUT%
tar -tf %OUT% >nul && echo tar readable: %OUT%
certutil -hashfile %OUT% SHA256
echo Now run: python verify_raw_backup.py %OUT%
