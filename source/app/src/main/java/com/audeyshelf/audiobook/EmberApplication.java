package com.audeyshelf.audiobook;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.audeyshelf.audiobook.data.SettingsManager;
import com.audeyshelf.audiobook.net.TLSSocketFactory;

import javax.net.ssl.HttpsURLConnection;

/**
 * Custom application class to initialize app-wide configurations such as theme.
 */
public class EmberApplication extends Application {

    private static final String TAG = "EmberApplication";

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Enable TLS 1.1 / TLS 1.2 on legacy Android (API 16-19)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(new TLSSocketFactory());
            } catch (Throwable t) {
                Log.w(TAG, "Could not initialize TLSSocketFactory", t);
            }
        }

        try {
            // Apply saved theme (Light, Dark / stak mode, or System default)
            SettingsManager settingsManager = new SettingsManager(this);
            settingsManager.applySavedTheme();
        } catch (Throwable ignored) {
        }
    }
}
