# ADB Runbook Checklist

Use this checklist for a fast manual verification on device.

## 1. Device reachable

```bash
adb devices
```

Pass:

- Target device is listed as `device`.

## 2. Clear logs and start focused logging

```bash
adb logcat -c
adb logcat -s CamperNavigatorService CarLauncher CarLauncherUtils ActivityTaskManager ActivityManager
```

Pass:

- Logging starts without errors.

## 3. Verify service exists

```bash
adb shell service list | grep camper_navigator
```

Pass:

- `camper_navigator` is listed.

## 4. Check current top activity

```bash
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

Pass:

- Shows either launcher home or `com.example.campernavigator/.MainActivity`.

## 5. Check persisted navigator mode

```bash
adb shell settings get secure camper_navigator_mode
```

Pass:

- `0` means HOME
- `1` means FULLSCREEN

## 6. Check LUM file

```bash
adb shell cat /data/system/camper_navigator_lum.properties
```

Pass:

- Contains `mode=`
- Contains `screen=`
- Contains `updatedAtEpochMs=`

## 7. Verify launcher home state

Action:

- Press the SystemUI HOME button.

Then run:

```bash
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

Pass:

- Top activity is launcher home.
- Secure setting is `0`.
- LUM contains `mode=HOME`.
- Embedded map is visible in launcher.
- Launcher shows no duplicate global HOME/Navi buttons.

## 8. Verify fullscreen state from SystemUI

Action:

- Press the SystemUI navigation button.

Then run:

```bash
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

Pass:

- Top activity is `com.example.campernavigator/.MainActivity`.
- Secure setting is `1`.
- LUM contains `mode=FULLSCREEN`.

## 9. Verify HOME after fullscreen

Action:

- From fullscreen Navigator, press the SystemUI HOME button.

Then run:

```bash
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

Pass:

- Top activity returns to launcher home.
- Secure setting returns to `0`.
- LUM returns to `mode=HOME`.

## 10. Verify navigator UI reaction in home mode

Action:

- Start fullscreen Navigator.
- Start a route if needed.
- Press SystemUI HOME.
- Observe the embedded map in launcher.

Pass:

- CCP/camera is shifted for home mode.
- Fullscreen-only UI is hidden.

## 11. Force HOME through service broadcast

```bash
adb shell am broadcast \
  -a com.asiks.camper.navigator.action.SET_MODE \
  --es com.asiks.camper.navigator.extra.MODE HOME \
  --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true
```

Pass:

- Launcher becomes visible.
- `settings get secure camper_navigator_mode` returns `0`.

## 12. Force FULLSCREEN through service broadcast

```bash
adb shell am broadcast \
  -a com.asiks.camper.navigator.action.SET_MODE \
  --es com.asiks.camper.navigator.extra.MODE FULLSCREEN \
  --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true
```

Pass:

- Navigator becomes visible.
- `settings get secure camper_navigator_mode` returns `1`.

## 13. Verify boot restore from FULLSCREEN

Action:

- Put system in fullscreen Navigator.

Check before reboot:

```bash
adb shell cat /data/system/camper_navigator_lum.properties
```

Reboot:

```bash
adb reboot
adb wait-for-device
```

Check after boot:

```bash
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
```

Pass:

- Navigator comes back fullscreen.
- Secure setting is `1`.
- LUM still contains `mode=FULLSCREEN`.

## 14. Verify boot restore from HOME

Action:

- Put system in launcher home.

Check before reboot:

```bash
adb shell cat /data/system/camper_navigator_lum.properties
```

Reboot:

```bash
adb reboot
adb wait-for-device
```

Check after boot:

```bash
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
```

Pass:

- Launcher home is visible after boot.
- Secure setting is `0`.
- LUM still contains `mode=HOME`.

## 15. Quick failure triage

If something fails, run:

```bash
adb logcat -d -s CamperNavigatorService CarLauncher CarLauncherUtils ActivityTaskManager ActivityManager
adb shell cat /data/system/camper_navigator_lum.properties
adb shell settings get secure camper_navigator_mode
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

Focus on:

- Service not receiving the mode change
- Wrong mode persisted in settings or LUM
- Wrong top activity after HOME/FULLSCREEN
- Launcher visible but embedded map missing

## Optional: Save a snapshot to a log file

Windows:

```bat
adb_runbook.bat snapshot-log
adb_runbook.bat snapshot-log my_snapshot.txt
```

Shell:

```bash
./adb_runbook.sh snapshot-log
./adb_runbook.sh snapshot-log my_snapshot.txt
```

Pass:

- A snapshot file is created.
- The file contains service list, top activity, secure mode, and LUM contents.