package com.asiks.camper.navigator;

/**
 * System-server interface for controlling the ASiKS Camper Navigator.
 *
 * MODE_HOME:
 *     Launcher/Home remains in the foreground.
 *
 * MODE_FULLSCREEN:
 *     Navigator becomes the foreground activity.
 */
interface ICamperNavigatorManager {

    const int MODE_HOME = 0;
    const int MODE_FULLSCREEN = 1;

    /**
     * Returns the current display mode for the current Android user.
     */
    int getMode();

    /**
     * Changes and persists the display mode.
     *
     * MODE_HOME:
     *     Navigator should not be forced to the foreground.
     *
     * MODE_FULLSCREEN:
     *     Navigator should be brought to the foreground.
     */
    void setMode(int mode);

    /**
     * Bring the Navigator to the foreground.
     */
    void showNavigator();

    /**
     * Leave the Navigator task in place but allow another foreground task,
     * normally Launcher/Home.
     */
    void hideNavigator();
}
