@echo off
setlocal

set ACTION=%1
if "%ACTION%"=="" set ACTION=snapshot
set LOGFILE=%2

where adb >nul 2>nul
if errorlevel 1 (
  echo adb not found in PATH
  exit /b 1
)

if /I "%ACTION%"=="snapshot" goto snapshot
if /I "%ACTION%"=="snapshot-log" goto snapshot_log
if /I "%ACTION%"=="logs-clear" goto logs_clear
if /I "%ACTION%"=="set-home" goto set_home
if /I "%ACTION%"=="set-fullscreen" goto set_fullscreen
if /I "%ACTION%"=="reboot-wait" goto reboot_wait
goto usage

:wait_device
adb wait-for-device >nul
goto :eof

:snapshot
call :wait_device
echo == service list ==
adb shell service list | findstr camper_navigator
echo == top activity ==
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
echo == secure setting camper_navigator_mode ==
adb shell settings get secure camper_navigator_mode
echo == LUM file ==
adb shell cat /data/system/camper_navigator_lum.properties
exit /b 0

:snapshot_log
if "%LOGFILE%"=="" (
  for /f %%i in ('powershell -NoProfile -Command "(Get-Date).ToString('yyyyMMdd_HHmmss')"') do set LOGFILE=adb_snapshot_%%i.txt
)
call :wait_device
(
  echo == service list ==
  adb shell service list ^| findstr camper_navigator
  echo == top activity ==
  adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
  echo == secure setting camper_navigator_mode ==
  adb shell settings get secure camper_navigator_mode
  echo == LUM file ==
  adb shell cat /data/system/camper_navigator_lum.properties
) > "%LOGFILE%"
echo Snapshot written to %LOGFILE%
type "%LOGFILE%"
exit /b 0

:logs_clear
adb logcat -c
echo Logs cleared. Start focused logging with:
echo adb logcat -s CamperNavigatorService CarLauncher CarLauncherUtils ActivityTaskManager ActivityManager
exit /b 0

:set_home
adb shell am broadcast -a com.asiks.camper.navigator.action.SET_MODE --es com.asiks.camper.navigator.extra.MODE HOME --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true
goto snapshot

:set_fullscreen
adb shell am broadcast -a com.asiks.camper.navigator.action.SET_MODE --es com.asiks.camper.navigator.extra.MODE FULLSCREEN --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true
goto snapshot

:reboot_wait
adb reboot
call :wait_device
echo Device is back online.
goto snapshot

:usage
echo Usage:
echo   adb_runbook.bat snapshot
echo   adb_runbook.bat snapshot-log [output-file]
echo   adb_runbook.bat logs-clear
echo   adb_runbook.bat set-home
echo   adb_runbook.bat set-fullscreen
echo   adb_runbook.bat reboot-wait
exit /b 1