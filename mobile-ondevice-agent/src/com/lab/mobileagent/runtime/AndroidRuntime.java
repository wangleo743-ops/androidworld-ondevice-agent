package com.lab.mobileagent.runtime;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Method;

/** Framework context for the already-authorized shell app_process on the dedicated emulator. */
public final class AndroidRuntime {
    private static volatile Context initializedShellContext;
    private AndroidRuntime() {}

    public static synchronized void initializeAndroidContextOnMainThread() throws Exception {
        if (Process.myUid() != 2000 || Process.myTid() != Process.myPid()
                || Looper.myLooper() == null || Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("android_context_main_thread_required");
        if (initializedShellContext != null) return;
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context base = (Context) getSystemContext.invoke(thread);
        if (base == null) throw new IllegalStateException("android_context_unavailable");
        initializedShellContext = new ContextWrapper(base) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
            }
            @Override public Context getApplicationContext() { return this; }
        };
    }

    public static Context executionContext() {
        Context context = initializedShellContext;
        if (context == null) throw new IllegalStateException("android_context_not_initialized");
        return context;
    }
}
