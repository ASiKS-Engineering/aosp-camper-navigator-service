package com.asiks.camper.navigator;

import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.List;

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

    private static final String NAVIGATOR_PACKAGE = "com.example.campernavigator";
    private static final String NAVIGATOR_ACTIVITY =
            "com.example.campernavigator.MainActivity";

    private final Object mLock = new Object();
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    private int mMode = MODE_HOME;

    private static final int MOVE_TASK_FLAGS =
            ActivityManager.MOVE_TASK_WITH_HOME
                    | ActivityManager.MOVE_TASK_NO_USER_ACTION;

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
                    setModeInternal(mode);
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
        }

        Slog.i(TAG, "User switching to " + userId
                + " mode=" + modeToString(mMode));

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

    private void setModeInternal(int mode) {
        if (mode != MODE_HOME && mode != MODE_FULLSCREEN) {
            throw new IllegalArgumentException("Unknown Navigator mode: " + mode);
        }

        final int userId;
        synchronized (mLock) {
            mMode = mode;
            userId = mCurrentUserId;
            writeModeLocked(userId, mode);
        }

        if (mode == MODE_FULLSCREEN) {
            showNavigatorInternal();
        } else {
            hideNavigatorInternal();
        }
    }

    private int readModeLocked(int userId) {
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

    private void showNavigatorInternal() {
        final int userId = getCurrentUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            final Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    NAVIGATOR_PACKAGE, NAVIGATOR_ACTIVITY));
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
}
