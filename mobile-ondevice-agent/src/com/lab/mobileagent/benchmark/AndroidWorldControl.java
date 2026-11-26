package com.lab.mobileagent.benchmark;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Process;
import com.lab.mobileagent.l3.ComputerControl;
import com.lab.mobileagent.model.Action;
import com.lab.mobileagent.model.Observation;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dedicated API33 emulator display-zero adapter. Never selected by production Control. */
public final class AndroidWorldControl implements ComputerControl {
    private final String component;
    private final BenchmarkContract.TaskSpec task;
    private final JSONArray commands=new JSONArray();
    private int width,height,actionCount,observations,focusChecks,focusRetries;
    private long focusRetryWaitNanos;
    private boolean closed,opened;
    private long boundary;
    private final int targetUid;
    private static final String[] HOMES={"com.android.launcher3","com.google.android.apps.nexuslauncher"};

    public AndroidWorldControl(Context context,BenchmarkContract.TaskSpec task) throws Exception {
        if(task==null)throw new SecurityException("benchmark_task_required");
        this.task=task;
        String qemu=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,"ro.kernel.qemu");
        if(Process.myUid()!=2000||Build.VERSION.SDK_INT!=33||!"1".equals(qemu))
            throw new SecurityException("isolated_api33_emulator_required");
        PackageManager pm=context.getPackageManager();
        Intent launcher=pm.getLaunchIntentForPackage(task.targetPackage);
        if(launcher==null)throw new IllegalStateException("benchmark_target_not_installed");
        ResolveInfo resolved=pm.resolveActivity(launcher,0);
        if(resolved==null||resolved.activityInfo==null||!task.targetPackage.equals(resolved.activityInfo.packageName)
                ||!resolved.activityInfo.enabled||!resolved.activityInfo.exported)
            throw new SecurityException("benchmark_target_launcher_invalid");
        component=new ComponentName(task.targetPackage,resolved.activityInfo.name).flattenToShortString();
        targetUid=resolved.activityInfo.applicationInfo.uid;
        boolean systemApp=(resolved.activityInfo.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0;
        if(!task.targetUidAllowed(targetUid,systemApp))throw new SecurityException("benchmark_target_uid_invalid");
    }
    private void active(){if(closed)throw new IllegalStateException("benchmark_control_closed");}
    private String[] foreground() throws Exception {
        // API 33 reports mCurrentFocus in DisplayContent.dump, not the windows subcommand.
        // Activity transitions may temporarily report a complete primary section with focus=null.
        // Re-read that one state only. Scope and parser errors still fail immediately, with no input replay.
        focusChecks++;final int[] reads={0};
        try{
            return AndroidWorldFocus.awaitPrimary(remainingMillis->{
                reads[0]++;
                return new String(nativeCallWithin("foreground_check",2*1024*1024,remainingMillis,
                        "/system/bin/dumpsys","window","displays"),java.nio.charset.StandardCharsets.UTF_8);
            },focused->{
                String pkg=focused[0];
                if(!task.allowedObservation(pkg,focused[1])
                        ||!(task.targetPackage.equals(pkg)||task.allowedTransient(pkg,focused[1])||(!opened&&Arrays.asList(HOMES).contains(pkg))))
                    throw new SecurityException("benchmark_foreground_out_of_scope");
            },System::nanoTime,millis->{
                long start=System.nanoTime();
                try{Thread.sleep(millis);}finally{focusRetryWaitNanos+=System.nanoTime()-start;}
            },2000,50);
        }finally{focusRetries+=Math.max(0,reads[0]-1);}
    }
    @Override public int displayId(){return 0;}
    @Override public Observation observe() throws Exception {
        active();String[] before=foreground();
        byte[] png=nativeCall("screenshot",16*1024*1024,"/system/bin/screencap","-p");
        long captured=System.nanoTime();String[] after=foreground();
        if(!Arrays.equals(before,after))throw new SecurityException("benchmark_screen_target_changed");
        if(task.targetPackage.equals(before[0]))opened=true;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(png,0,png.length,options);
        if(options.outWidth<1||options.outHeight<1||options.outWidth>4096||options.outHeight>4096
                ||!"image/png".equals(options.outMimeType))throw new IllegalStateException("benchmark_invalid_screenshot");
        width=options.outWidth;height=options.outHeight;observations++;
        return new Observation(png,width,height,0,before[0],before[1],captured,boundary,true);
    }
    @Override public void execute(Action action) throws Exception {
        active();BenchmarkContract.action(task,action);foreground();
        boundary=System.nanoTime();
        switch(action.name){
            case "open_app":
                String result=new String(nativeCall("open_target_app",65536,"/system/bin/am","start","-W","--display","0",
                        "-n",component,"-a",Intent.ACTION_MAIN,"-c",Intent.CATEGORY_LAUNCHER),java.nio.charset.StandardCharsets.UTF_8);
                if(!result.contains("Status: ok")||result.contains("Error:"))throw new IllegalStateException("benchmark_target_open_failed");
                opened=true;break;
            case "click": input("tap",x(action.args,"x"),y(action.args,"y"));break;
            case "swipe":input("swipe",x(action.args,"start_x"),y(action.args,"start_y"),x(action.args,"end_x"),y(action.args,"end_y"),"400");break;
            case "go_back":input("keyevent","4");break;
            case "wait":Thread.sleep((long)(1000*action.args.getDouble("seconds")));break;
            case "take_screenshot":break;
            default:throw new SecurityException("benchmark_action_not_allowed");
        }
        actionCount++;
        // Timing explicitly counts this short rendering allowance as control time; it is not proof of UI completion.
        if(!"wait".equals(action.name))Thread.sleep(250);
        foreground();
    }
    private String x(JSONObject a,String k)throws Exception{if(width<1)throw new IllegalStateException("benchmark_observation_required");return String.valueOf(Math.round(a.getInt(k)*(width-1)/999.0));}
    private String y(JSONObject a,String k)throws Exception{if(height<1)throw new IllegalStateException("benchmark_observation_required");return String.valueOf(Math.round(a.getInt(k)*(height-1)/999.0));}
    private void input(String...tail)throws Exception{
        if(!opened)throw new SecurityException("benchmark_open_target_first");
        String[] argv=new String[tail.length+3];argv[0]="/system/bin/input";argv[1]="-d";argv[2]="0";
        System.arraycopy(tail,0,argv,3,tail.length);nativeCall("input_"+tail[0],65536,argv);
    }
    private byte[] nativeCall(String operation,int limit,String...argv)throws Exception{
        return nativeCallWithin(operation,limit,15000,argv);
    }
    private static void joinWithin(Thread thread,long deadline)throws InterruptedException{
        long remaining=deadline-System.nanoTime();
        if(remaining>0)thread.join(remaining/1_000_000L,(int)(remaining%1_000_000L));
    }
    private byte[] nativeCallWithin(String operation,int limit,long timeoutMillis,String...argv)throws Exception{
        if(!(argv[0].equals("/system/bin/dumpsys")||argv[0].equals("/system/bin/screencap")
                ||argv[0].equals("/system/bin/am")||argv[0].equals("/system/bin/input")))throw new SecurityException("benchmark_executable_denied");
        if(timeoutMillis<1||timeoutMillis>15000)throw new IllegalArgumentException("benchmark_native_budget_invalid");
        long start=System.nanoTime(),deadline=start+timeoutMillis*1_000_000L;boolean success=false;
        java.lang.Process process=new ProcessBuilder(argv).start();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();AtomicReference<Exception> error=new AtomicReference<>();
        Thread reader=new Thread(()->{
            try(InputStream in=process.getInputStream()){
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){if(bytes.size()+n>limit)throw new IllegalStateException("benchmark_native_output_limit");bytes.write(buffer,0,n);}
            }catch(Exception e){error.set(e);process.destroyForcibly();}
        },"benchmark-native-output");reader.setDaemon(true);reader.start();
        Thread errors=new Thread(()->{try(InputStream in=process.getErrorStream()){byte[] b=new byte[1024];int count=0,n;while((n=in.read(b))!=-1){count+=n;if(count>65536){process.destroyForcibly();break;}}}catch(Exception ignored){}},"benchmark-native-errors");
        errors.setDaemon(true);errors.start();
        try{
            process.getOutputStream().close();
            long remaining=deadline-System.nanoTime();
            if(remaining<=0||!process.waitFor(remaining,TimeUnit.NANOSECONDS))throw new IllegalStateException("benchmark_native_timeout");
            joinWithin(reader,deadline);joinWithin(errors,deadline);
            if(reader.isAlive()||error.get()!=null||process.exitValue()!=0)throw new IllegalStateException("benchmark_native_failed");
            success=true;return bytes.toByteArray();
        }finally{
            process.destroyForcibly();
            commands.put(new JSONObject().put("operation",operation).put("wall_seconds",(System.nanoTime()-start)/1e9).put("success",success));
        }
    }
    public JSONObject report() throws Exception{return new JSONObject().put("display_id",0).put("task_name",task.taskName).put("target_package",task.targetPackage)
            .put("resolved_launcher_component",component).put("task_catalog_version",BenchmarkContract.CATALOG_VERSION)
            .put("target_uid",targetUid).put("actions",actionCount).put("observations",observations).put("closed",closed)
            .put("focus_checks",focusChecks).put("focus_null_retries",focusRetries)
            .put("focus_retry_sleep_seconds",focusRetryWaitNanos/1e9).put("focus_transition_budget_seconds",2)
            .put("focus_wait_definition","only explicit null focus is retried; wait is nested in control/observation time; inputs are never replayed")
            .put("input_scope","dedicated_emulator_primary_display_not_virtual_display_isolation")
            .put("capture_semantics","fresh_screencap_call_after_dispatch_not_compositor_completion_proof").put("native_timings",commands);}
    @Override public void close(){closed=true;/* Preserve app state until and after the official scorer; no force-stop or reset. */}
}
