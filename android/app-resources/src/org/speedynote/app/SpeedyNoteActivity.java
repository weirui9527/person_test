package org.speedynote.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import org.qtproject.qt.android.bindings.QtActivity;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Custom Activity for SpeedyNote.
 * 
 * Extends QtActivity to:
 * 1. Handle Activity results from the PDF file picker (BUG-A003)
 * 2. Handle Activity results from the .snbx package importer (Phase 2)
 * 3. Enable high-rate stylus input via requestUnbufferedDispatch() (BUG-A004)
 * 4. Provide system dark mode detection for theme synchronization (BUG-A007)
 * 
 * This is necessary because:
 * - PDF picker: We need to process the file picker result while SAF permission is valid
 * - Package importer: Same SAF handling for .snbx files
 * - Stylus input: Android batches touch events at 60Hz by default; we want 240Hz
 * - Dark mode: Qt doesn't automatically detect Android's system theme setting
 */
public class SpeedyNoteActivity extends QtActivity {
    private static final String TAG = "SpeedyNoteActivity";
    
    // Singleton reference for JNI calls
    private static SpeedyNoteActivity sInstance;
    
    // Stylus eraser tool detection (BUG-A008: Hardware eraser not working)
    // Qt on Android doesn't properly translate Android's TOOL_TYPE_ERASER
    // to QPointingDevice::PointerType::Eraser, so we detect it here and
    // expose it via JNI for C++ to query.
    private static volatile boolean sEraserToolActive = false;
    
    // ===== Native Touch Tracking (for gesture reliability) =====
    // Qt's touch event layer can lose track of touch points after sleep/wake
    // or app switching. These values are tracked at the native Android level
    // and queried via JNI when Qt's touch count seems wrong.
    
    /** Current number of active touch points, tracked at the native Android level. */
    private static volatile int sNativeTouchCount = 0;
    
    /** Last time any touch event was received (epoch ms), for stale detection. */
    private static volatile long sLastTouchTimestamp = 0;
    
    /** Positions of active touch points (max 2 tracked): x1, y1, x2, y2. */
    private static volatile float[] sTouchPositions = new float[4];
    
    // Cached content view for unbuffered dispatch (performance optimization)
    // Avoids calling findViewById() on every touch event at 240Hz
    private View mCachedContentView = null;
    
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        // Java-side boot log: brackets the native library load that happens
        // inside QtActivity.onCreate (System.loadLibrary of Qt + MuPDF + the app
        // lib). If the process dies loading those .so files, the C++ main() never
        // runs, so only this Java log proves how far startup got. Appends to the
        // same speedynote_boot.log the C++ side uses, giving one ordered timeline.
        // Capture the external files dir up front so the C++ logger (which runs
        // later on the Qt thread) can write to the exact same MTP-visible file.
        try {
            File d = getExternalFilesDir(null);
            if (d != null)
                sBootLogDir = d.getAbsolutePath();
        } catch (Throwable ignored) {
        }
        jboot("onCreate enter");
        try {
            super.onCreate(savedInstanceState);
            jboot("super.onCreate returned (native libs loaded, Qt thread started)");
        } catch (Throwable t) {
            jboot("super.onCreate THREW: " + t);
            throw t;
        }
        sInstance = this;
        jboot("onCreate done");
    }

    @Override
    protected void onResume() {
        jboot("onResume (activity lifecycle OK)");
        super.onResume();
    }

    private static String sBootLogDir = null;

    /** Absolute path of the app's external files dir, shared with the C++ logger. */
    public static String bootLogDir() {
        return sBootLogDir;
    }

    /**
     * Called from C++ main() via JNI to append a line to the boot log. This is a
     * decisive probe that does NOT depend on the C++ side managing to open the log
     * file itself: if native main() runs, this Java write (to a proven-writable
     * dir) will land. Its presence/absence separates "main never ran" from "main
     * ran but the C++ logger failed to write here".
     */
    public static void logFromNative(String msg) {
        try {
            if (sBootLogDir == null)
                return;
            File log = new File(sBootLogDir, "speedynote_boot.log");
            String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            try (FileWriter fw = new FileWriter(log, true)) {
                fw.write(ts + " [native-cb] " + msg + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Append a timestamped line to the app's external-files boot log. */
    private void jboot(String msg) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null)
                dir = getFilesDir();
            File log = new File(dir, "speedynote_boot.log");
            String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            try (FileWriter fw = new FileWriter(log, true)) {
                fw.write(ts + " [java] " + msg + "\n");
            }
        } catch (Throwable ignored) {
            // Logging must never itself break startup.
        }
    }
    
    @Override
    protected void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }
    
    /**
     * Check if the system is in dark mode.
     * Called from C++ via JNI to sync Qt's palette with Android's system theme.
     * 
     * @return true if dark mode is enabled, false otherwise
     */
    public static boolean isDarkMode() {
        if (sInstance == null) {
            Log.w(TAG, "isDarkMode: Activity not available, defaulting to light mode");
            return false;
        }
        
        Configuration config = sInstance.getResources().getConfiguration();
        int nightMode = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = (nightMode == Configuration.UI_MODE_NIGHT_YES);
        Log.d(TAG, "isDarkMode: " + isDark + " (uiMode=" + config.uiMode + ")");
        return isDark;
    }
    
    /**
     * Check if the stylus eraser tool is currently active.
     * Called from C++ via JNI because Qt doesn't properly detect eraser tool type.
     * 
     * @return true if eraser tip is being used, false for pen tip or other input
     */
    public static boolean isEraserToolActive() {
        return sEraserToolActive;
    }
    
    // ===== Native Touch Query Methods (called from C++ via JNI) =====
    
    /**
     * Get the current native touch count.
     * Called from C++ to verify Qt's touch state when gestures seem unreliable.
     * 
     * @return Number of fingers currently touching the screen (0-10)
     */
    public static int getNativeTouchCount() {
        return sNativeTouchCount;
    }
    
    /**
     * Get milliseconds since last touch event.
     * Used to detect if native touch state is stale.
     * 
     * @return Time in milliseconds since last touch event
     */
    public static long getTimeSinceLastTouch() {
        return System.currentTimeMillis() - sLastTouchTimestamp;
    }
    
    /**
     * Get native touch positions (x1, y1, x2, y2).
     * Returns positions of first 2 touch points for pinch gesture verification.
     * 
     * @return float array with [x1, y1, x2, y2] in screen coordinates
     */
    public static float[] getNativeTouchPositions() {
        return sTouchPositions;
    }
    
    /**
     * Intercept all touch events for:
     * 1. Requesting unbuffered dispatch (240Hz stylus input)
     * 2. Detecting eraser tool type (BUG-A008 fix)
     * 
     * On API 31+, this tells Android to deliver touch/stylus events at the
     * hardware's native rate (e.g., 240Hz) instead of batching them at 60Hz.
     * This results in smoother, more responsive drawing.
     * 
     * Performance: All operations here are O(1) with cached values.
     * - Content view is cached (avoids findViewById at 240Hz)
     * - Eraser check uses early-exit loop
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Detect eraser tool type before Qt processes the event
        // Only check for stylus events (optimization: skip finger events)
        int toolType = event.getToolType(0);
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            sEraserToolActive = (toolType == MotionEvent.TOOL_TYPE_ERASER);
        }
        
        // ===== Track native touch count for gesture reliability =====
        // Qt's touch tracking can become corrupted after sleep/wake.
        // Track ground truth at native Android level for JNI verification.
        int action = event.getActionMasked();
        
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            sNativeTouchCount = event.getPointerCount();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            sNativeTouchCount = 0;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            sNativeTouchCount = event.getPointerCount() - 1;
        } else if (action == MotionEvent.ACTION_MOVE) {
            sNativeTouchCount = event.getPointerCount();
        }
        
        sLastTouchTimestamp = System.currentTimeMillis();
        
        // Track positions of first 2 touch points (for pinch gesture verification)
        if (event.getPointerCount() >= 1) {
            sTouchPositions[0] = event.getX(0);
            sTouchPositions[1] = event.getY(0);
        }
        if (event.getPointerCount() >= 2) {
            sTouchPositions[2] = event.getX(1);
            sTouchPositions[3] = event.getY(1);
        }
        
        // Request unbuffered dispatch for high-rate stylus input (API 31+)
        // Use cached content view to avoid findViewById() overhead at 240Hz
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (mCachedContentView == null) {
                mCachedContentView = findViewById(android.R.id.content);
            }
            if (mCachedContentView != null) {
                mCachedContentView.requestUnbufferedDispatch(event);
            }
        }
        return super.dispatchTouchEvent(event);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
        
        // First, let our PDF helper try to handle it
        if (PdfFileHelper.handleActivityResult(requestCode, resultCode, data)) {
            Log.d(TAG, "PdfFileHelper handled the result");
            return;
        }
        
        // Try the package import helper
        if (ImportHelper.handleActivityResult(requestCode, resultCode, data)) {
            Log.d(TAG, "ImportHelper handled the result");
            return;
        }
        
        // If not handled, pass to Qt's default handling
        super.onActivityResult(requestCode, resultCode, data);
    }
}

