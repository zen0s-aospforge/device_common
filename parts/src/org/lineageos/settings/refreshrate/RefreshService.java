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

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;

public class RefreshService extends Service {

    private static final String TAG = "RefreshService";
    private static final boolean DEBUG = true;

    private String mPreviousApp = "";
    private RefreshUtils mRefreshUtils;
    private IActivityTaskManager mActivityTaskManager;
    private PowerManager mPowerManager;
    
    // Use a separate thread for processing to avoid blocking
    private HandlerThread mHandlerThread;
    private Handler mProcessingHandler;
    private Handler mMainHandler;
    
    // Cache for quick lookups
    private String mCachedForegroundApp = "";
    private long mLastCheckTime = 0;
    private static final long MIN_CHECK_INTERVAL_MS = 50; // Minimum time between checks

    private BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Screen off - reset to default rate to save battery
                if (DEBUG) Log.d(TAG, "Screen off - resetting refresh rate");
                mPreviousApp = "";
                // Could optionally force 60Hz here for power saving
                // mRefreshUtils.forceRefreshRate(60f);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                // Screen on - check current app
                if (DEBUG) Log.d(TAG, "Screen on - checking current app");
                mProcessingHandler.post(() -> checkCurrentApp());
            }
        }
    };
    
    private BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                boolean isPowerSaveMode = mPowerManager.isPowerSaveMode();
                if (DEBUG) Log.d(TAG, "Power save mode changed: " + isPowerSaveMode);
                
                if (isPowerSaveMode) {
                    // Force 60Hz in power save mode
                    mRefreshUtils.forceRefreshRate(60f);
                } else {
                    // Re-check current app when exiting power save
                    mProcessingHandler.post(() -> checkCurrentApp());
                }
            }
        }
    };

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "Creating service");
        super.onCreate();
        
        // Initialize on main thread
        mRefreshUtils = new RefreshUtils(this);
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mMainHandler = new Handler(Looper.getMainLooper());
        
        // Create background thread for processing
        mHandlerThread = new HandlerThread("RefreshServiceThread");
        mHandlerThread.start();
        mProcessingHandler = new Handler(mHandlerThread.getLooper());
        
        // Register for task stack changes
        try {
            mActivityTaskManager = ActivityTaskManager.getService();
            mActivityTaskManager.registerTaskStackListener(mTaskListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register task stack listener", e);
        }
        
        // Register receivers
        registerReceivers();
        
        // Initial check
        mProcessingHandler.post(() -> checkCurrentApp());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting service");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service");
        
        // Unregister task listener
        if (mActivityTaskManager != null) {
            try {
                mActivityTaskManager.unregisterTaskStackListener(mTaskListener);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister task stack listener", e);
            }
        }
        
        // Unregister receivers
        unregisterReceiver(mScreenReceiver);
        unregisterReceiver(mPowerReceiver);
        
        // Clean up handler thread
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
        
        super.onDestroy();
    }

    private void registerReceivers() {
        // Screen on/off receiver
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mScreenReceiver, screenFilter);
        
        // Power save mode receiver
        IntentFilter powerFilter = new IntentFilter();
        powerFilter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        registerReceiver(mPowerReceiver, powerFilter);
    }

    /**
     * Optimized task stack listener for faster app detection
     */
    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            // Debounce rapid task changes
            long currentTime = System.currentTimeMillis();
            if (currentTime - mLastCheckTime < MIN_CHECK_INTERVAL_MS) {
                return;
            }
            mLastCheckTime = currentTime;
            
            // Process on background thread
            mProcessingHandler.post(() -> checkCurrentApp());
        }
        
        @Override
        public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
            // Handle picture-in-picture mode
            if (DEBUG) Log.d(TAG, "Activity pinned: " + packageName);
        }
        
        @Override
        public void onActivityUnpinned() {
            // Re-check when exiting PiP
            mProcessingHandler.post(() -> checkCurrentApp());
        }
    };
    
    /**
     * Check and update refresh rate for current foreground app
     */
    private void checkCurrentApp() {
        try {
            // Don't process if in power save mode (already forced to 60Hz)
            if (mPowerManager.isPowerSaveMode()) {
                return;
            }
            
            final RootTaskInfo info = mActivityTaskManager.getFocusedRootTaskInfo();
            if (info == null || info.topActivity == null) {
                return;
            }
            
            String foregroundApp = info.topActivity.getPackageName();
            
            // Quick cache check to avoid redundant processing
            if (foregroundApp.equals(mCachedForegroundApp)) {
                return;
            }
            mCachedForegroundApp = foregroundApp;
            
            // Only update if app actually changed
            if (!foregroundApp.equals(mPreviousApp)) {
                if (DEBUG) Log.d(TAG, "App changed: " + mPreviousApp + " -> " + foregroundApp);
                
                mPreviousApp = foregroundApp;
                
                // Update refresh rate on main thread for system settings access
                mMainHandler.post(() -> {
                    if (!mRefreshUtils.isAppInList) {
                        mRefreshUtils.getOldRate();
                    }
                    mRefreshUtils.setRefreshRate(foregroundApp);
                });
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get focused task", e);
        } catch (Exception e) {
            Log.e(TAG, "Error checking current app", e);
        }
    }
    
    /**
     * Public method to force a refresh rate check
     * Can be called from other components if needed
     */
    public void forceCheck() {
        mProcessingHandler.post(() -> {
            mCachedForegroundApp = ""; // Clear cache
            checkCurrentApp();
        });
    }
}