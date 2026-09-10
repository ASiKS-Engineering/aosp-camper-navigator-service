package com.asiks.camper.navigator;

import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Environment;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.server.SystemService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * System-server controller for the Camper Navigator foreground task.
 *
 * HOME:
 *   Launcher remains foreground. Navigator is kept alive if already running.
 *
 * FULLSCREEN:
 *   Navigator is started/reused and moved to the foreground.
 *
 * The Navigator is intentionally not a HOME activity.
 */
public final class CamperNavigatorService extends SystemService {
    private static final String TAG = "CamperNavigatorService";
    public static final String SERVICE_NAME = "camper_navigator";

    private static final String SETTING_MODE = "camper_navigator_mode";
    private static final String LUM_FILE_NAME = "camper_navigator_lum.properties";

    public static final String ACTION_SET_MODE =
            "com.asiks.camper.navigator.action.SET_MODE";
    public static final String EXTRA_MODE =
            "com.asiks.camper.navigator.extra.MODE";
    public static final String EXTRA_APPLY_SCREEN_TRANSITION =
            "com.asiks.camper.navigator.extra.APPLY_SCREEN_TRANSITION";
    public static final String MODE_VALUE_HOME = "HOME";
    public static final String MODE_VALUE_FULLSCREEN = "FULLSCREEN";

    private static final String NAVIGATION_UI_MODE_EXTRA =
            "com.example.campernavigator.extra.NAVIGATION_UI_MODE";
        private static final String NAVIGATOR_WARMUP_ACTION =
            "com.example.campernavigator.action.PREWARM";
        private static final String NAVIGATOR_WARMUP_REASON_EXTRA =
            "com.example.campernavigator.extra.PREWARM_REASON";

    private static final String NAVIGATOR_PACKAGE = "com.example.campernavigator";
    private static final String NAVIGATOR_ACTIVITY =
            "com.example.campernavigator.MainActivity";
        private static final String NAVIGATOR_WARMUP_SERVICE =
            "com.example.campernavigator.NavigatorWarmupService";

    private final AtomicFile mLumFile = new AtomicFile(
            new File(Environment.getDataSystemDirectory(), LUM_FILE_NAME));

    private final Object mLock = new Object();
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    private int mMode = MODE_HOME;

    private final BroadcastReceiver mModeRequestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SET_MODE.equals(intent.getAction())) {
                return;
            }

            String modeValue = intent.getStringExtra(EXTRA_MODE);
            int mode = parseMode(modeValue);
            if (mode == -1) {
                Slog.w(TAG, "Ignoring invalid navigator mode request: " + modeValue);
                return;
            }

            boolean applyScreenTransition = intent.getBooleanExtra(
                    EXTRA_APPLY_SCREEN_TRANSITION, false);
            setModeInternal(mode, applyScreenTransition, "broadcast");
        }
    };

    private final BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                return;
            }

            synchronized (mLock) {
                writeLumLocked(mCurrentUserId, mMode);
            }
            Slog.i(TAG, "Persisted LUM on shutdown: user=" + mCurrentUserId
                    + " mode=" + modeToString(mMode));
        }
    };

    public static final int MODE_HOME = ICamperNavigatorManager.Stub.MODE_HOME;
    public static final int MODE_FULLSCREEN =
            ICamperNavigatorManager.Stub.MODE_FULLSCREEN;

    private final ICamperNavigatorManager.Stub mBinder =
            new ICamperNavigatorManager.Stub() {
                @Override
                public int getMode() {
                    enforceCaller();
                    synchronized (mLock) {
                        return mMode;
                    }
                }

                @Override
                public void setMode(int mode) {
                    enforceCaller();
                    setModeInternal(mode, true, "binder");
                }

                @Override
                public void showNavigator() {
                    enforceCaller();
                    showNavigatorInternal();
                }

                @Override
                public void hideNavigator() {
                    enforceCaller();
                    hideNavigatorInternal();
                }
            };

    public CamperNavigatorService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Starting Camper Navigator system service");
        publishBinderService(SERVICE_NAME, mBinder);

        IntentFilter modeFilter = new IntentFilter(ACTION_SET_MODE);
        getContext().registerReceiverAsUser(
            mModeRequestReceiver,
            UserHandle.ALL,
            modeFilter,
            null,
            null);

        IntentFilter shutdownFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        getContext().registerReceiverAsUser(
            mShutdownReceiver,
            UserHandle.ALL,
            shutdownFilter,
            null,
            null);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_BOOT_COMPLETED) {
            return;
        }

        synchronized (mLock) {
            mMode = readModeLocked(mCurrentUserId);
        }

        Slog.i(TAG, "Boot complete: user=" + mCurrentUserId
                + " mode=" + modeToString(mMode));

        prewarmNavigatorProcess("boot_completed");

        if (mMode == MODE_FULLSCREEN) {
            showNavigatorInternal();
        }
    }

    @Override
    public void onUserStarting(TargetUser user) {
        if (!user.isFull()) {
            return;
        }
        Slog.i(TAG, "User starting: " + user.getUserIdentifier());
        prewarmNavigatorProcess("user_starting");
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        if (!to.isFull()) {
            return;
        }

        final int userId = to.getUserIdentifier();
        synchronized (mLock) {
            mCurrentUserId = userId;
            mMode = readModeLocked(userId);
            writeLumLocked(userId, mMode);
        }

        Slog.i(TAG, "User switching to " + userId
                + " mode=" + modeToString(mMode));

        prewarmNavigatorProcess("user_switching");

        if (mMode == MODE_FULLSCREEN) {
            showNavigatorInternal();
        } else {
            hideNavigatorInternal();
        }
    }

    @Override
    public void onUserStopped(TargetUser user) {
        final int userId = user.getUserIdentifier();
        synchronized (mLock) {
            if (mCurrentUserId == userId && userId != UserHandle.USER_SYSTEM) {
                mCurrentUserId = UserHandle.USER_SYSTEM;
                mMode = MODE_HOME;
            }
        }
    }

    private void setModeInternal(int mode, boolean applyScreenTransition, String source) {
        if (mode != MODE_HOME && mode != MODE_FULLSCREEN) {
            throw new IllegalArgumentException("Unknown Navigator mode: " + mode);
        }

        final int userId;
        final int previousMode;
        synchronized (mLock) {
            previousMode = mMode;
            mMode = mode;
            userId = mCurrentUserId;
            writeModeLocked(userId, mode);
            writeLumLocked(userId, mode);
        }

        Slog.i(TAG, "Navigator mode updated by " + source + ": user=" + userId
                + " " + modeToString(previousMode) + " -> " + modeToString(mode)
                + ", applyScreenTransition=" + applyScreenTransition);

        if (!applyScreenTransition) {
            return;
        }

        if (mode == MODE_FULLSCREEN) {
            showNavigatorInternal();
        } else {
            hideNavigatorInternal();
        }
    }

    private int readModeLocked(int userId) {
        int lumMode = readLumModeLocked(userId);
        if (lumMode != -1) {
            return lumMode;
        }

        try {
            return Settings.Secure.getIntForUser(
                    getContext().getContentResolver(),
                    SETTING_MODE,
                    MODE_HOME,
                    userId) == MODE_FULLSCREEN ? MODE_FULLSCREEN : MODE_HOME;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Cannot read mode for user " + userId, e);
            return MODE_HOME;
        }
    }

    private void writeModeLocked(int userId, int mode) {
        try {
            Settings.Secure.putIntForUser(
                    getContext().getContentResolver(),
                    SETTING_MODE,
                    mode,
                    userId);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Cannot persist mode for user " + userId, e);
        }
    }

    private int readLumModeLocked(int userId) {
        Properties props = new Properties();

        try (FileInputStream inputStream = mLumFile.openRead()) {
            props.load(inputStream);
        } catch (IOException e) {
            return -1;
        }

        int storedUserId;
        try {
            storedUserId = Integer.parseInt(props.getProperty("userId", "-1"));
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Invalid LUM userId, ignoring file", e);
            return -1;
        }

        if (storedUserId != userId) {
            return -1;
        }

        return parseMode(props.getProperty("mode"));
    }

    private void writeLumLocked(int userId, int mode) {
        Properties props = new Properties();
        props.setProperty("userId", Integer.toString(userId));
        props.setProperty("mode", modeToString(mode));
        props.setProperty("screen", screenToString(mode));
        props.setProperty("updatedAtEpochMs", Long.toString(System.currentTimeMillis()));

        FileOutputStream outputStream = null;
        try {
            outputStream = mLumFile.startWrite();
            props.store(outputStream, "Camper Navigator LUM");
            mLumFile.finishWrite(outputStream);
        } catch (IOException e) {
            Slog.e(TAG, "Cannot write LUM file", e);
            if (outputStream != null) {
                mLumFile.failWrite(outputStream);
            }
        }
    }

    private void showNavigatorInternal() {
        final int userId = getCurrentUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            final Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    NAVIGATOR_PACKAGE, NAVIGATOR_ACTIVITY));
            intent.putExtra(NAVIGATION_UI_MODE_EXTRA, MODE_VALUE_FULLSCREEN);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            final ActivityOptions options = ActivityOptions.makeBasic();
            getContext().startActivityAsUser(
                    intent, options.toBundle(), UserHandle.of(userId));

            // If the activity was already in a task, the launch flags above plus
            // singleTask in the app manifest reuse it. The framework start path
            // is used here instead of calling hidden ATMS internals from a
            // vendor module.
            Slog.i(TAG, "Navigator foreground requested for user " + userId);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Unable to foreground Navigator", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void prewarmNavigatorProcess(String reason) {
        final int userId = getCurrentUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            final Intent intent = new Intent(NAVIGATOR_WARMUP_ACTION);
            intent.setComponent(new ComponentName(
                    NAVIGATOR_PACKAGE, NAVIGATOR_WARMUP_SERVICE));
            intent.putExtra(NAVIGATOR_WARMUP_REASON_EXTRA, reason);
            getContext().startServiceAsUser(intent, UserHandle.of(userId));
            Slog.i(TAG, "Navigator warmup requested for user " + userId
                    + " reason=" + reason);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Unable to prewarm Navigator", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void hideNavigatorInternal() {
        // Do not finish Navigator: HOME means Launcher is foreground while the
        // Navigator task remains available for the next FULLSCREEN transition.
        // The HOME task is explicitly started so that the transition is
        // deterministic even when HOME mode is changed from Navigator itself.
        final int userId = getCurrentUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            final Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            getContext().startActivityAsUser(
                    home, ActivityOptions.makeBasic().toBundle(),
                    UserHandle.of(userId));
            Slog.i(TAG, "HOME foreground requested for user " + userId);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Unable to foreground HOME", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private int getCurrentUserId() {
        synchronized (mLock) {
            return mCurrentUserId;
        }
    }

    private void enforceCaller() {
        getContext().enforceCallingPermission(
                "android.permission.WRITE_SECURE_SETTINGS",
                "Camper Navigator service requires WRITE_SECURE_SETTINGS");
    }

    private static String modeToString(int mode) {
        return mode == MODE_FULLSCREEN ? "FULLSCREEN" : "HOME";
    }

    private static String screenToString(int mode) {
        return mode == MODE_FULLSCREEN ? "navigator_fullscreen" : "launcher_home";
    }

    private static int parseMode(String modeValue) {
        if (MODE_VALUE_FULLSCREEN.equals(modeValue)) {
            return MODE_FULLSCREEN;
        }
        if (MODE_VALUE_HOME.equals(modeValue)) {
            return MODE_HOME;
        }
        return -1;
    }
}
