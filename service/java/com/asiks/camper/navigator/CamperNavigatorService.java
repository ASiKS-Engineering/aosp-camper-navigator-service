package com.asiks.camper.navigator;

import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import com.android.server.SystemService;


/**
 * System-server controller for the Camper Navigator UI mode.
 *
 * The Navigator Activity is always hosted by CarLauncher's TaskView.
 *
 * HOME:
 *   Navigator remains embedded in the TaskView.
 *   CarLauncher shows the audio card.
 *
 * FULLSCREEN:
 *   Navigator remains embedded in the TaskView.
 *   CarLauncher hides the audio card.
 *
 * No Activity/task switching is performed by this service.
 */
public final class CamperNavigatorService extends SystemService {
    private static final String TAG = "CamperNavigatorService";
    public static final String SERVICE_NAME = "camper_navigator";

    private static final String SETTING_MODE = "camper_navigator_mode";
	private static final String ACTION_NAVIGATION_UI_MODE_CHANGED =
			"com.example.campernavigator.action.NAVIGATION_UI_MODE_CHANGED";
	private static final String EXTRA_NAVIGATION_UI_MODE =
			"com.example.campernavigator.extra.NAVIGATION_UI_MODE";
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
    }

	@Override
	public void onBootPhase(int phase) {
		if (phase != PHASE_BOOT_COMPLETED) {
			return;
		}

		synchronized (mLock) {
			mMode = MODE_HOME;
		}

		Slog.i(
				TAG,
				"Boot complete: user=" + mCurrentUserId
						+ " mode=" + modeToString(mMode));

		prewarmNavigatorProcess("boot_completed");

		publishNavigationUiMode(mMode);
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
            mMode = MODE_HOME;
        }

        Slog.i(TAG, "User switching to " + userId
                + " mode=" + modeToString(mMode));

        prewarmNavigatorProcess("user_switching");

        publishNavigationUiMode(mMode);
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

	private void publishNavigationUiMode(int mode) {
		Intent intent = new Intent(ACTION_NAVIGATION_UI_MODE_CHANGED);
		intent.putExtra(
				EXTRA_NAVIGATION_UI_MODE,
				modeToString(mode));

		intent.setPackage("com.android.car.carlauncher");
		getContext().sendBroadcastAsUser(
				intent,
				UserHandle.of(getCurrentUserId()));

		Intent navigatorIntent = new Intent(ACTION_NAVIGATION_UI_MODE_CHANGED);
		navigatorIntent.putExtra(
				EXTRA_NAVIGATION_UI_MODE,
				modeToString(mode));

		navigatorIntent.setPackage(NAVIGATOR_PACKAGE);
		getContext().sendBroadcastAsUser(
				navigatorIntent,
				UserHandle.of(getCurrentUserId()));
	}

	private void setModeInternal(
			int mode,
			boolean applyScreenTransition,
			String source) {

		if (mode != MODE_HOME && mode != MODE_FULLSCREEN) {
			throw new IllegalArgumentException(
					"Unknown Navigator mode: " + mode);
		}

		final int userId;
		final int previousMode;

		synchronized (mLock) {
			previousMode = mMode;

			if (previousMode == mode) {
				return;
			}

			mMode = mode;
			userId = mCurrentUserId;

			writeModeLocked(userId, mode);
		}

		Slog.i(
				TAG,
				"Navigator mode updated by " + source
						+ ": user=" + userId
						+ " " + modeToString(previousMode)
						+ " -> " + modeToString(mode)
						+ ", applyScreenTransition="
						+ applyScreenTransition);

		publishNavigationUiMode(mMode);
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
