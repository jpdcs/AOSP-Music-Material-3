package com.android.music;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

public class MusicApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Apply dynamic colors to all activities in the app
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
