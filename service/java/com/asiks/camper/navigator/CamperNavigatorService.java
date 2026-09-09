package com.asiks.camper.navigator;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;

/**
 * AOSP system-server service for the ASiKS Camper Navigator.
 *
 * Boot behavior:
 *
 *   HOME:
 *       Launcher is allowed to remain the foreground application.
 *
 *   FULLSCREEN:
 *       After PHASE_BOOT_COMPLETED, the Navigator Activity is started/
 *       brought to the foreground.
 *
 * The Navigator is NOT a HOME application.
 */
public final class CamperNavigatorService extends SystemService {

    private static final String TAG =
            "CamperNavigatorService";

    /**
     * Binder service name.
     */
    public static final String SERVICE_NAME =
            "camper_navigator";

    /**
     * Persistent Settings.Secure key.
     */
    private static final String SETTING_MODE =
            "camper_navigator_mode";

    /**
     * Navigator application package.
     *
     * IMPORTANT:
     * Verify this against the final Navigator APK.
     */
    private static final String NAVIGATOR_PACKAGE =
            "com.asiks.campernavigator";

    /**
     * Navigator main Activity.
     */
    private static final String NAVIGATOR_ACTIVITY =
            "com.asiks.campernavigator.MainActivity";

    /**
     * HOME mode.
     */
    public static final int MODE_HOME =
            ICamperNavigatorManager.Stub.MODE_HOME;

    /**
     * FULLSCREEN mode.
     */
    public static final int MODE_FULLSCREEN =
            ICamperNavigatorManager.Stub.MODE_FULLSCREEN;

    private final Object mLock = new Object();

    private final Context mContext;

    private int mCurrentUserId =
            UserHandle.USER_SYSTEM;

    private int mMode =
            MODE_HOME;

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

        mContext = context;
    }

    // ------------------------------------------------------------------------
    // SystemService lifecycle
    // ------------------------------------------------------------------------

    @Override
    public void onStart() {
        Slog.i(TAG, "Starting Camper Navigator service");

        publishBinderService(
                SERVICE_NAME,
                mBinder);
    }

    @Override
    public void onBootPhase(int phase) {

        /*
         * Important:
         *
         * PHASE_BOOT_COMPLETED is intentional here.
         *
         * We want Launcher/Home to have the opportunity to establish the
         * normal HOME task first.
         */
        if (phase == PHASE_BOOT_COMPLETED) {

            Slog.i(
                    TAG,
                    "Boot completed - restoring Navigator state");

            restoreForCurrentUser();
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {

        synchronized (mLock) {

            mCurrentUserId =
                    userHandle;

            mMode =
                    readModeLocked(userHandle);
        }

        Slog.i(
                TAG,
                "User switched to "
                        + userHandle
                        + ", mode="
                        + modeToString(mMode));

        if (mMode == MODE_FULLSCREEN) {

            showNavigatorInternal();

        } else {

            hideNavigatorInternal();
        }
    }

    @Override
    public void onStartUser(int userHandle) {

        Slog.i(
                TAG,
                "User started: "
                        + userHandle);
    }

    @Override
    public void onStopUser(int userHandle) {

        Slog.i(
                TAG,
                "User stopped: "
                        + userHandle);
    }

    // ------------------------------------------------------------------------
    // Boot restore
    // ------------------------------------------------------------------------

    private void restoreForCurrentUser() {

        synchronized (mLock) {

            mMode =
                    readModeLocked(
                            mCurrentUserId);
        }

        Slog.i(
                TAG,
                "Restoring mode for user "
                        + mCurrentUserId
                        + ": "
                        + modeToString(mMode));

        if (mMode == MODE_FULLSCREEN) {

            showNavigatorInternal();
        }

        /*
         * MODE_HOME deliberately does nothing.
         *
         * Launcher/Home is already allowed to remain the foreground
         * application.
         */
    }

    // ------------------------------------------------------------------------
    // Mode handling
    // ------------------------------------------------------------------------

    private void setModeInternal(int mode) {

        if (mode != MODE_HOME
                && mode != MODE_FULLSCREEN) {

            throw new IllegalArgumentException(
                    "Unknown Navigator mode: "
                            + mode);
        }

        final int userId;

        synchronized (mLock) {

            mMode = mode;

            userId =
                    mCurrentUserId;

            writeModeLocked(
                    userId,
                    mode);
        }

        Slog.i(
                TAG,
                "Mode changed for user "
                        + userId
                        + ": "
                        + modeToString(mode));

        if (mode == MODE_FULLSCREEN) {

            showNavigatorInternal();

        } else {

            hideNavigatorInternal();
        }
    }

    // ------------------------------------------------------------------------
    // Persistent Settings
    // ------------------------------------------------------------------------

    private int readModeLocked(int userId) {

        try {

            final int value =
                    Settings.Secure.getIntForUser(
                            mContext.getContentResolver(),
                            SETTING_MODE,
                            MODE_HOME,
                            userId);

            if (value == MODE_FULLSCREEN) {
                return MODE_FULLSCREEN;
            }

        } catch (RuntimeException e) {

            Slog.w(
                    TAG,
                    "Unable to read Navigator mode "
                            + "for user "
                            + userId,
                    e);
        }

        return MODE_HOME;
    }

    private void writeModeLocked(
            int userId,
            int mode) {

        try {

            Settings.Secure.putIntForUser(
                    mContext.getContentResolver(),
                    SETTING_MODE,
                    mode,
                    userId);

        } catch (RuntimeException e) {

            Slog.e(
                    TAG,
                    "Unable to persist Navigator mode "
                            + "for user "
                            + userId,
                    e);
        }
    }

    // ------------------------------------------------------------------------
    // Navigator Activity
    // ------------------------------------------------------------------------

    private void showNavigatorInternal() {

        final long token =
                Binder.clearCallingIdentity();

        try {

            final Intent intent =
                    new Intent();

            intent.setComponent(
                    new ComponentName(
                            NAVIGATOR_PACKAGE,
                            NAVIGATOR_ACTIVITY));

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            final ActivityOptions options =
                    ActivityOptions.makeBasic();

            Slog.i(
                    TAG,
                    "Starting Navigator for user "
                            + getCurrentUserId());

            /*
             * This is the deliberately simple first implementation.
             *
             * During Android 17 integration we should replace this with the
             * ActivityTaskManager internal path that can explicitly reuse
             * and move the existing Navigator task to the foreground.
             */
            mContext.startActivityAsUser(
                    intent,
                    options.toBundle(),
                    UserHandle.of(
                            getCurrentUserId()));

        } catch (RuntimeException e) {

            Slog.e(
                    TAG,
                    "Unable to start Navigator",
                    e);

        } finally {

            Binder.restoreCallingIdentity(
                    token);
        }
    }

    /**
     * HOME mode does not destroy the Navigator task.
     *
     * This is important:
     *
     *   HOME != Navigator stopped
     *
     * The Navigator should retain its task so it can be restored quickly.
     *
     * The Android 17 integration will add the explicit task-to-front logic
     * required to move Launcher/Home to the foreground.
     */
    private void hideNavigatorInternal() {

        Slog.i(
                TAG,
                "Navigator switched to HOME mode; "
                        + "Launcher should remain foreground");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private int getCurrentUserId() {

        synchronized (mLock) {

            return mCurrentUserId;
        }
    }

    /**
     * Initial development implementation.
     *
     * IMPORTANT:
     *
     * Before exposing the Binder service to arbitrary applications, replace
     * this with a signature-level permission check.
     *
     * The Navigator application should receive that permission in its
     * AndroidManifest.xml.
     */
    private void enforceCaller() {

        /*
         * Intentionally empty in the initial repository.
         *
         * Security integration belongs in the Android 17 AOSP integration.
         */
    }

    private static String modeToString(int mode) {

        switch (mode) {

            case MODE_HOME:
                return "HOME";

            case MODE_FULLSCREEN:
                return "FULLSCREEN";

            default:
                return "UNKNOWN(" + mode + ")";
        }
    }
}
