package com.termux.x11;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class MaterialYouApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
