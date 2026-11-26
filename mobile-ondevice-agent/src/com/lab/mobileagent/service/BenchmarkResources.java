package com.lab.mobileagent.service;

import android.os.Build;
import android.os.Process;

/** Explicit test-only factory; normal SessionResourceManager.local still forbids display zero. */
public final class BenchmarkResources {
    private BenchmarkResources() {}
    public static SessionResourceManager isolatedEmulator(String run) throws Exception {
        String qemu=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class)
                .invoke(null,"ro.kernel.qemu");
        if (Process.myUid()!=2000 || Build.VERSION.SDK_INT!=33 || !"1".equals(qemu)
                || run==null || !run.matches("aw-[a-z0-9_-]{1,70}"))
            throw new SecurityException("isolated_api33_emulator_required");
        return new ResourceOperationQueue(run,0,"agent-exclusive");
    }
}
