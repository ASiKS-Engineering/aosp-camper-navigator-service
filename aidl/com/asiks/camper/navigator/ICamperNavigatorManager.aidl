package com.asiks.camper.navigator;

interface ICamperNavigatorManager {
    const int MODE_HOME = 0;
    const int MODE_FULLSCREEN = 1;

    int getMode();
    void setMode(int mode);
    void showNavigator();
    void hideNavigator();
}
