package com.audeyshelf.audiobook;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.audeyshelf.audiobook.data.SettingsManager;

/**
 * Custom application class to initialize app-wide configurations such as theme.
 */
public class EmberApplication extends Application {

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Apply saved theme (Light, Dark / stak mode, or System default)
            SettingsManager settingsManager = new SettingsManager(this);
            settingsManager.applySavedTheme();
        } catch (Throwable ignored) {
        }
    }
}
