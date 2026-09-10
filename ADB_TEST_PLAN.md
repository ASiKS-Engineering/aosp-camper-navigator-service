# ADB Test Plan

This test plan verifies the unified HOME/FULLSCREEN flow across SystemUI, Launcher, Camper Navigator, and the LUM persistence file.

## Scope

- HOME button shows Launcher home.
- Launcher home shows the embedded Camper Navigator map.
- Camper Navigator receives HOME mode and applies the CCP shift and reduced UI.
- Navigation button shows Camper Navigator in fullscreen.
- Only SystemUI exposes the global HOME and Navigation buttons.
- LUM state is written during mode changes and on shutdown.
- LUM state is read on boot and restores the correct screen.

## Relevant Components

- Service: `CamperNavigatorService`
- Launcher: `CarLauncher`, `ControlBarActivity`, `WidgetHostActivity`
- Navigator: `com.example.campernavigator/.MainActivity`
- LUM file: `/data/system/camper_navigator_lum.properties`

## Preconditions

1. Build contains the updated versions of:
   - `aosp-camper-navigator-service`
   - `aosp-car-launcher`
   - `aosp-car-systemUI`
   - `aosp_camper_navigator_src`
2. Device is booted and visible via `adb devices`.
3. The app `com.example.campernavigator` is installed in the image.
4. The service integration patches are present in the platform build.

## Useful Commands

### Continuous logs

```bash
adb logcat -c
adb logcat -s CamperNavigatorService CarLauncher CarLauncherUtils ActivityTaskManager ActivityManager
```

### Check top activity

```bash
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

Expected values:

- Launcher HOME: `com.android.car.carlauncher/.CarLauncher` or the active launcher activity
- Navigator FULLSCREEN: `com.example.campernavigator/.MainActivity`

### Confirm launcher does not expose duplicate global buttons

This is primarily a visual verification.

Expected:

- No dedicated global `Home` button is visible inside the Launcher content.
- No dedicated global `Navigation` button is visible inside the Launcher content.
- Global screen switching is only possible through SystemUI.

### Check current persisted secure setting

```bash
adb shell settings get secure camper_navigator_mode
```

Expected values:

- `0` for HOME
- `1` for FULLSCREEN

### Inspect the LUM file

```bash
adb shell cat /data/system/camper_navigator_lum.properties
```

Expected keys:

- `userId=`
- `mode=HOME` or `mode=FULLSCREEN`
- `screen=launcher_home` or `screen=navigator_fullscreen`
- `updatedAtEpochMs=`

### Force mode through the service broadcast

HOME:

```bash
adb shell am broadcast \
  -a com.asiks.camper.navigator.action.SET_MODE \
  --es com.asiks.camper.navigator.extra.MODE HOME \
  --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true
```

FULLSCREEN:

```bash
adb shell am broadcast \
  -a com.asiks.camper.navigator.action.SET_MODE \
  --es com.asiks.camper.navigator.extra.MODE FULLSCREEN \
  --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true
```

## Test Case 1: Service availability after boot

### Steps

```bash
adb shell service list | grep camper_navigator
```

### Expected

- `camper_navigator` is present in the binder service list.
- `logcat` contains `Starting Camper Navigator system service`.

## Test Case 2: HOME mode from Launcher lifecycle

### Steps

1. Bring launcher home to the foreground.
2. Wait 2 to 3 seconds.
3. Run:

```bash
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

### Expected

- Top activity is the launcher home.
- Secure setting is `0`.
- LUM file contains:
  - `mode=HOME`
  - `screen=launcher_home`
- `logcat` contains a `CamperNavigatorService` mode update to HOME.

## Test Case 2a: No duplicate global controls inside Launcher

### Steps

1. Bring launcher home to the foreground.
2. Inspect the launcher content area visually.

### Expected

- Launcher does not render a second global HOME button.
- Launcher does not render a second global Navigation button.
- The embedded map remains visible as content, not as a separate global navigation control.

## Test Case 3: FULLSCREEN mode from the SystemUI navigation button

### Steps

1. From launcher home, tap the bottom navigation button in SystemUI.
2. Wait until the Navigator is fully visible.
3. Run:

```bash
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

### Expected

- Top activity is `com.example.campernavigator/.MainActivity`.
- Secure setting is `1`.
- LUM file contains:
  - `mode=FULLSCREEN`
  - `screen=navigator_fullscreen`
- `logcat` shows that the service received the mode request and requested Navigator foreground.

## Test Case 4: HOME button while Navigator was fullscreen

### Steps

1. Start from FULLSCREEN mode.
2. Tap the SystemUI HOME button.
3. Wait 2 to 3 seconds.
4. Run:

```bash
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

### Expected

- Top activity is the launcher home.
- Secure setting is `0`.
- LUM file contains `mode=HOME`.
- Embedded map area in launcher remains available.
- No new fullscreen launch of Navigator occurs.

## Test Case 5: Embedded map on Launcher home

### Steps

1. Go to HOME.
2. Confirm visually that the map card is present in the launcher.
3. Optionally run:

```bash
adb shell dumpsys activity activities | grep com.example.campernavigator
```

### Expected

- Launcher home remains foreground.
- Navigator task still exists and can be reused.
- Map card is visible inside Launcher.

## Test Case 6: Navigator HOME UI reaction

### Steps

1. Start FULLSCREEN Navigator.
2. Begin navigation if needed so CCP behavior is visible.
3. Press HOME.
4. Observe the launcher embedded map view.

### Expected

- Navigator switches to HOME UI mode.
- CCP/camera padding is shifted for the launcher layout.
- Fullscreen-only overlays are hidden.
- The left-side fullscreen UI does not remain visible.

## Test Case 7: LUM write on explicit mode changes

### Steps

Run both broadcasts manually:

```bash
adb shell am broadcast \
  -a com.asiks.camper.navigator.action.SET_MODE \
  --es com.asiks.camper.navigator.extra.MODE FULLSCREEN \
  --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true

adb shell cat /data/system/camper_navigator_lum.properties

adb shell am broadcast \
  -a com.asiks.camper.navigator.action.SET_MODE \
  --es com.asiks.camper.navigator.extra.MODE HOME \
  --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true

adb shell cat /data/system/camper_navigator_lum.properties
```

### Expected

- After FULLSCREEN broadcast: `mode=FULLSCREEN`
- After HOME broadcast: `mode=HOME`
- `updatedAtEpochMs` changes on each write

## Test Case 8: LUM write on shutdown

### Steps

1. Put system in FULLSCREEN or HOME.
2. Confirm current LUM contents.
3. Reboot the device:

```bash
adb reboot
```

4. After reconnect, inspect the file:

```bash
adb wait-for-device
adb shell cat /data/system/camper_navigator_lum.properties
```

### Expected

- The file still contains the last mode before reboot.
- `logcat` around shutdown should have shown LUM persistence.

## Test Case 9: Restore FULLSCREEN from LUM on boot

### Steps

1. Enter FULLSCREEN mode.
2. Verify the file contains `mode=FULLSCREEN`.
3. Reboot.
4. After boot completion, run:

```bash
adb wait-for-device
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
```

### Expected

- Navigator returns to foreground automatically.
- Top activity is `com.example.campernavigator/.MainActivity`.
- Secure setting remains `1`.
- LUM file remains `mode=FULLSCREEN`.

## Test Case 10: Restore HOME from LUM on boot

### Steps

1. Enter HOME mode.
2. Verify the file contains `mode=HOME`.
3. Reboot.
4. After boot completion, run:

```bash
adb wait-for-device
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
```

### Expected

- Launcher home remains the visible screen after boot.
- Secure setting remains `0`.
- LUM file remains `mode=HOME`.

## Test Case 11: Direct sanity checks for mode recovery

If UI behavior is ambiguous, force both states and verify the top activity:

```bash
adb shell am broadcast \
  -a com.asiks.camper.navigator.action.SET_MODE \
  --es com.asiks.camper.navigator.extra.MODE HOME \
  --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true

adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"

adb shell am broadcast \
  -a com.asiks.camper.navigator.action.SET_MODE \
  --es com.asiks.camper.navigator.extra.MODE FULLSCREEN \
  --ez com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION true

adb shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'"
```

## Failure Triage

### Problem: SystemUI nav button does nothing

Check:

```bash
adb logcat -s CamperNavigatorService CarSystemBarButton
```

Look for:

- received broadcast action
- invalid mode parsing
- failed foreground request

### Problem: FULLSCREEN works, but boot restore does not

Check:

```bash
adb shell cat /data/system/camper_navigator_lum.properties
adb logcat -d -s CamperNavigatorService
```

Look for:

- missing or stale LUM file
- wrong `userId`
- boot phase log without `showNavigatorInternal()` follow-up

### Problem: Launcher appears, but map card is empty

Check:

```bash
adb logcat -s CarLauncher CarLauncherViewModel ActivityTaskManager
adb shell dumpsys activity activities | grep com.example.campernavigator
```

Look for:

- Navigator task exists but TaskView was not recreated
- no maps intent resolution
- task vanished after HOME transition

### Problem: Navigator returns, but still looks fullscreen inside home card

Check:

```bash
adb shell settings get secure camper_navigator_mode
adb shell cat /data/system/camper_navigator_lum.properties
```

Then verify visually that the HOME layout path is active.

This means the mode persistence worked, but the Navigator UI reaction likely failed inside app-level state handling.

## Recommended Execution Order

1. Test Case 1
2. Test Case 2
3. Test Case 3
4. Test Case 4
5. Test Case 5
6. Test Case 6
7. Test Case 8
8. Test Case 9
9. Test Case 10

## Pass Criteria

The feature is considered complete when all conditions hold:

- One central mode flow controls HOME and FULLSCREEN.
- SystemUI navigation opens fullscreen through the service.
- HOME returns to Launcher and keeps embedded map behavior.
- LUM is updated on each mode transition and on shutdown.
- Boot restore chooses the correct visible screen from LUM.