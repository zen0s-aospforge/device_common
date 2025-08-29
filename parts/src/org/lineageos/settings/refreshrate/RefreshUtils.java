/*
 * Copyright (C) 2020-2024 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.refreshrate;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;

import java.util.concurrent.Executor;

public final class RefreshUtils {

    private static final String TAG = "RefreshUtils";
    private static final boolean DEBUG = true;
    
    private static final String REFRESH_CONTROL = "refresh_control";
    
    // System settings keys
    private static final String KEY_PEAK_REFRESH_RATE = "peak_refresh_rate";
    private static final String KEY_MIN_REFRESH_RATE = "min_refresh_rate";
    private static final String KEY_USER_REFRESH_RATE = "user_refresh_rate";
    
    // Smooth switching property - reduces flicker
    private static final String PROP_SMOOTH_DISPLAY_SWITCH = "vendor.display.smooth_switch";

    private static float defaultMaxRate;
    private static float defaultMinRate;
    
    private Context mContext;
    private Handler mHandler;
    private DisplayManager mDisplayManager;
    private WindowManager mWindowManager;
    protected static boolean isAppInList = false;
    
    // App states
    protected static final int STATE_DEFAULT = 0;
    protected static final int STATE_STANDARD = 1;
    protected static final int STATE_EXTREME = 2;

    // Refresh rates
    private static final float REFRESH_STATE_DEFAULT = 120f;
    private static final float REFRESH_STATE_STANDARD = 60f;
    private static final float REFRESH_STATE_EXTREME = 120f;
    
    // Storage prefixes
    private static final String REFRESH_STANDARD = "refresh.standard=";
    private static final String REFRESH_EXTREME = "refresh.extreme=";
    
    private SharedPreferences mSharedPrefs;
    private float mLastSetMaxRate = -1;
    private float mLastSetMinRate = -1;
    private String mLastPackage = "";
    
    // Debouncing
    private static final long DEBOUNCE_DELAY_MS = 100;
    private Runnable mPendingRateChange;

    protected RefreshUtils(Context context) {
        mContext = context;
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        mHandler = new Handler(Looper.getMainLooper());
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mWindowManager = context.getSystemService(WindowManager.class);
        
        // Enable smooth display switching if supported
        enableSmoothSwitching();
        
        // Get default rates
        getOldRate();
    }

    public static void startService(Context context) {
        context.startServiceAsUser(new Intent(context, RefreshService.class),
                UserHandle.CURRENT);
    }
    
    private void enableSmoothSwitching() {
        try {
            // This property tells the display HAL to use smooth transitions
            SystemProperties.set(PROP_SMOOTH_DISPLAY_SWITCH, "true");
            
            // Also try to set animation transition scale for smoother switching
            Settings.Global.putFloat(mContext.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f);
        } catch (Exception e) {
            Log.w(TAG, "Could not enable smooth switching: " + e.getMessage());
        }
    }

    private void writeValue(String profiles) {
        mSharedPrefs.edit().putString(REFRESH_CONTROL, profiles).apply();
    }

    protected void getOldRate() {
        defaultMaxRate = Settings.System.getFloat(mContext.getContentResolver(), 
                KEY_PEAK_REFRESH_RATE, REFRESH_STATE_DEFAULT);
        defaultMinRate = Settings.System.getFloat(mContext.getContentResolver(), 
                KEY_MIN_REFRESH_RATE, REFRESH_STATE_DEFAULT);
    }

    private String getValue() {
        String value = mSharedPrefs.getString(REFRESH_CONTROL, null);

        if (value == null || value.isEmpty()) {
            value = REFRESH_STANDARD + ":" + REFRESH_EXTREME;
            writeValue(value);
        }
        return value;
    }

    protected void writePackage(String packageName, int mode) {
        String value = getValue();
        value = value.replace(packageName + ",", "");
        String[] modes = value.split(":");
        String finalString;

        switch (mode) {
            case STATE_STANDARD:
                modes[0] = modes[0] + packageName + ",";
                break;
            case STATE_EXTREME:
                modes[1] = modes[1] + packageName + ",";
                break;
        }

        finalString = modes[0] + ":" + modes[1];
        writeValue(finalString);
    }

    protected int getStateForPackage(String packageName) {
        String value = getValue();
        String[] modes = value.split(":");
        int state = STATE_DEFAULT;
        if (modes[0].contains(packageName + ",")) {
            state = STATE_STANDARD;
        } else if (modes[1].contains(packageName + ",")) {
            state = STATE_EXTREME;
        }
        return state;
    }

    /**
     * Optimized refresh rate setting with debouncing and smooth transition
     */
    protected void setRefreshRate(String packageName) {
        // Skip if same package
        if (packageName.equals(mLastPackage)) {
            return;
        }
        mLastPackage = packageName;
        
        // Cancel pending rate change
        if (mPendingRateChange != null) {
            mHandler.removeCallbacks(mPendingRateChange);
        }
        
        // Debounce the rate change to avoid rapid switching
        mPendingRateChange = () -> applyRefreshRate(packageName);
        mHandler.postDelayed(mPendingRateChange, DEBOUNCE_DELAY_MS);
    }
    
    /**
     * Apply refresh rate with optimizations to reduce flicker
     */
    private void applyRefreshRate(String packageName) {
        String value = getValue();
        String modes[];
        float targetMaxRate = defaultMaxRate;
        float targetMinRate = defaultMinRate;
        isAppInList = false;

        if (value != null) {
            modes = value.split(":");

            if (modes[0].contains(packageName + ",")) {
                targetMaxRate = REFRESH_STATE_STANDARD;
                if (targetMinRate > targetMaxRate) {
                    targetMinRate = targetMaxRate;
                }
                isAppInList = true;
            } else if (modes[1].contains(packageName + ",")) {
                targetMaxRate = REFRESH_STATE_EXTREME;
                if (targetMinRate > targetMaxRate) {
                    targetMinRate = targetMaxRate;
                }
                isAppInList = true;
            }
        }
        
        // Only update if rates actually changed
        if (targetMaxRate == mLastSetMaxRate && targetMinRate == mLastSetMinRate) {
            if (DEBUG) Log.d(TAG, "Rates unchanged, skipping update");
            return;
        }
        
        mLastSetMaxRate = targetMaxRate;
        mLastSetMinRate = targetMinRate;
        
        // Use a more optimized approach for setting refresh rates
        setRatesOptimized(targetMinRate, targetMaxRate);
    }
    
    /**
     * Optimized method to set refresh rates with minimal flicker
     */
    private void setRatesOptimized(float minRate, float maxRate) {
        try {
            // Method 1: Try to set both rates in a single transaction (reduces flicker)
            mHandler.post(() -> {
                // Disable animations temporarily for smoother transition
                float originalScale = Settings.Global.getFloat(
                        mContext.getContentResolver(),
                        Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f);
                
                try {
                    // Temporarily reduce animation scale
                    Settings.Global.putFloat(mContext.getContentResolver(),
                            Settings.Global.WINDOW_ANIMATION_SCALE, 0.0f);
                    
                    // Set rates in quick succession
                    Settings.System.putFloat(mContext.getContentResolver(), 
                            KEY_MIN_REFRESH_RATE, minRate);
                    Settings.System.putFloat(mContext.getContentResolver(), 
                            KEY_PEAK_REFRESH_RATE, maxRate);
                    
                    // Also try to set user preference rate (some ROMs use this)
                    Settings.System.putFloat(mContext.getContentResolver(),
                            KEY_USER_REFRESH_RATE, maxRate);
                    
                    // Restore animation scale after a brief delay
                    mHandler.postDelayed(() -> {
                        Settings.Global.putFloat(mContext.getContentResolver(),
                                Settings.Global.WINDOW_ANIMATION_SCALE, originalScale);
                    }, 50);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error setting refresh rates: " + e.getMessage());
                    // Fallback to standard method
                    Settings.System.putFloat(mContext.getContentResolver(), 
                            KEY_MIN_REFRESH_RATE, minRate);
                    Settings.System.putFloat(mContext.getContentResolver(), 
                            KEY_PEAK_REFRESH_RATE, maxRate);
                }
            });
            
            if (DEBUG) {
                Log.d(TAG, "Set refresh rates - Min: " + minRate + " Max: " + maxRate);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to set refresh rates", e);
        }
    }
    
    /**
     * Force immediate refresh rate update (use sparingly)
     */
    public void forceRefreshRate(float rate) {
        try {
            // Set both min and max to same value to force specific rate
            Settings.System.putFloat(mContext.getContentResolver(), 
                    KEY_MIN_REFRESH_RATE, rate);
            Settings.System.putFloat(mContext.getContentResolver(), 
                    KEY_PEAK_REFRESH_RATE, rate);
            Settings.System.putFloat(mContext.getContentResolver(),
                    KEY_USER_REFRESH_RATE, rate);
                    
            if (DEBUG) Log.d(TAG, "Forced refresh rate to: " + rate);
        } catch (Exception e) {
            Log.e(TAG, "Failed to force refresh rate", e);
        }
    }
}