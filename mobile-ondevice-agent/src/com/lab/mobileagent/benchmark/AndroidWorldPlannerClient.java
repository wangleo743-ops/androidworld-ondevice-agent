package com.lab.mobileagent.benchmark;

import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SharedMemory;
import android.system.OsConstants;
import com.lab.mobileagent.l1.Decision;
import com.lab.mobileagent.l1.ModelConnection;
import com.lab.mobileagent.l1.StrictJson;
import com.lab.mobileagent.model.Observation;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Separately signed, ordinary-UID benchmark worker; production planner/authority are unchanged. */
public final class AndroidWorldPlannerClient implements BenchmarkPlanner {
    private static final String PKG="com.lab.androidworldplanner", AUTH=PKG+".model";
    private final String key,model,effort,endpoint;
    private final boolean allowHttp;
    private final String taskName,target;
    private final BenchmarkContract.TaskSpec spec;
    private final long deadline;
    private final int uid,pid;
    private final Lease lease;
    private final JSONObject verified;
    private int calls;
    private boolean closed;

    public AndroidWorldPlannerClient(Context context,String key,String model,String effort,long deadlineNanos)throws Exception{
        this(context,key,model,effort,deadlineNanos,BenchmarkContract.TASK,BenchmarkContract.TARGET);
    }
    public AndroidWorldPlannerClient(Context context,String key,String model,String effort,long deadlineNanos,
            String taskName,String targetPackage)throws Exception{
        this(context,key,model,effort,deadlineNanos,taskName,targetPackage,ModelConnection.DEFAULT_ENDPOINT,false);
    }
    public AndroidWorldPlannerClient(Context context,String key,String model,String effort,long deadlineNanos,
            String taskName,String targetPackage,String endpoint,boolean allowHttp)throws Exception{
        this.endpoint=ModelConnection.endpoint(endpoint,allowHttp);this.allowHttp=allowHttp;
        spec=BenchmarkContract.task(taskName);this.taskName=taskName;target=spec.targetPackage;
        if(!target.equals(targetPackage))throw fail("benchmark_planner_target_mismatch");
        if(Process.myUid()!=2000||key==null||!key.matches("[!-~]{1,4096}")
                ||model==null||!model.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                ||!ModelConnection.validEffort(effort)||deadlineNanos<=System.nanoTime())
            throw fail("invalid_benchmark_planner_config");
        this.key=key;this.model=model;this.effort=effort;deadline=deadlineNanos;
        Context shell=context.createPackageContext("com.android.shell",0);
        uid=verifyPackage(shell);
        Lease candidate=new Lease();
        try{
            Bundle binding=new Bundle();binding.putString("task_name",taskName);binding.putString("target_package",target);
            String nonce=UUID.randomUUID().toString();Bundle receipt=candidate.call("identity",nonce,binding);
            verify(receipt,nonce);pid=receipt.getInt("pid");
            if(!receipt.getBoolean("inject_events_denied")||!receipt.getBoolean("dump_denied"))throw fail("planner_privilege_mismatch");
            verified=new JSONObject().put("package",PKG).put("uid",uid).put("pid",pid).put("host_uid",2000)
                .put("signer_sha256",AndroidWorldBuildPin.SHA256).put("inject_events_denied",true).put("dump_denied",true)
                .put("task_name",taskName).put("target",target).put("profile",BenchmarkContract.PROFILE).put("success",true);
            lease=candidate;
        }catch(Exception|Error failure){candidate.close();throw failure;}
    }
    private static IllegalStateException fail(String code){return new IllegalStateException(code);}
    private static int verifyPackage(Context context)throws Exception{
        PackageManager pm=context.getPackageManager();
        PackageInfo info=pm.getPackageInfo(PKG,PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_PERMISSIONS);
        ProviderInfo provider=pm.resolveContentProvider(AUTH,0);
        int uid=info.applicationInfo.uid;
        if(provider==null||!PKG.equals(provider.packageName)||!AUTH.equals(provider.authority)
                ||!(PKG+".PlannerProvider").equals(provider.name)||!PKG.equals(provider.processName)
                ||!provider.exported||!"android.permission.DUMP".equals(provider.readPermission)
                ||!"android.permission.DUMP".equals(provider.writePermission)||provider.grantUriPermissions
                ||info.sharedUserId!=null||uid<10000||uid>19999||provider.applicationInfo.uid!=uid
                ||!Arrays.equals(pm.getPackagesForUid(uid),new String[]{PKG})
                ||(info.applicationInfo.flags&(ApplicationInfo.FLAG_DEBUGGABLE|ApplicationInfo.FLAG_SYSTEM))!=0
                ||!Arrays.equals(info.requestedPermissions,new String[]{"android.permission.INTERNET"})
                ||info.signingInfo==null||info.signingInfo.getApkContentsSigners().length!=1)
            throw fail("benchmark_planner_package_untrusted");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
        StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!AndroidWorldBuildPin.SHA256.contentEquals(hex))throw fail("benchmark_planner_signer_mismatch");
        return uid;
    }
    private void verify(Bundle receipt,String nonce){
        if(receipt==null||!nonce.equals(receipt.getString("challenge"))||receipt.getInt("uid",-1)!=uid
                ||receipt.getInt("pid",-1)<=0||receipt.getInt("pid")==Process.myPid()||receipt.getInt("caller_uid",-1)!=2000
                ||!target.equals(receipt.getString("target"))||!taskName.equals(receipt.getString("task_name"))||receipt.getInt("protocol",0)!=2)
            throw fail("benchmark_planner_identity_mismatch");
    }
    @Override public JSONObject identity()throws Exception{return new JSONObject(verified.toString()).put("calls",calls).put("closed",closed);}
    @Override public Decision decide(String task,Observation frame,JSONArray history)throws Exception{
        spec.validateGoal(task);
        if(closed||!lease.binder.isBinderAlive())throw fail("benchmark_planner_closed_or_exited");
        if(frame==null||frame.displayId!=0||frame.png==null||frame.png.length>12*1024*1024||frame.png.length<24
                ||history==null||history.length()>64||task==null||task.length()>4000||deadline<=System.nanoTime())
            throw fail("invalid_benchmark_model_input");
        if(!spec.allowedObservation(frame.packageName,frame.component))throw fail("benchmark_observation_out_of_scope");
        String text=history.toString();if(text.length()>131072)throw fail("model_history_too_large");
        String nonce=UUID.randomUUID().toString();
        try(SharedMemory image=SharedMemory.create("androidworld-frame",frame.png.length)){
            ByteBuffer writable=image.mapReadWrite();try{writable.put(frame.png);}finally{SharedMemory.unmap(writable);}
            if(!image.setProtect(OsConstants.PROT_READ))throw fail("benchmark_image_not_readonly");
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();
            try(ParcelFileDescriptor input=pipe[0];ParcelFileDescriptor output=pipe[1]){
                Bundle data=new Bundle();data.putString("task",task);data.putString("history",text);data.putString("key",key);
                data.putString("task_name",taskName);data.putString("target_package",target);
                data.putString("model",model);data.putString("effort",effort);data.putLong("dead",deadline);
                data.putString("responses_endpoint",endpoint);data.putBoolean("allow_http",allowHttp);
                data.putParcelable("png",image);data.putParcelable("reply",output);
                data.putInt("width",frame.width);data.putInt("height",frame.height);data.putInt("display",0);
                data.putString("package",frame.packageName);data.putString("component",frame.component);
                data.putLong("captured",frame.capturedNanos);data.putLong("observed",frame.observedNanos);
                data.putLong("boundary",frame.actionBoundaryNanos);data.putBoolean("new_frame",frame.frameAfterAction);
                Bundle receipt=lease.call("decide",nonce,data);verify(receipt,nonce);
                if(receipt.getInt("pid")!=pid)throw fail("benchmark_planner_process_changed");
                output.close();data.clear();calls++;
                JSONObject result=StrictJson.parseObject(read(input,Math.min(deadline-System.nanoTime(),TimeUnit.SECONDS.toNanos(125))));
                if(!nonce.equals(result.optString("challenge"))||result.optInt("uid",-1)!=uid||result.optInt("pid",-1)!=pid
                        ||!target.equals(result.optString("target"))||!taskName.equals(result.optString("task_name"))||!"live_model".equals(result.optString("source")))
                    throw fail("benchmark_planner_reply_mismatch");
                if(result.has("error")){
                    String error=result.optString("error");throw fail(error.matches("[a-z][a-z0-9_]{0,80}")?error:"benchmark_planner_failed");
                }
                if(!lease.binder.isBinderAlive())throw fail("benchmark_planner_exited");
                return Decision.parse(result.getJSONObject("decision").toString(),false);
            }
        }
    }
    private static String read(ParcelFileDescriptor fd,long nanos)throws Exception{
        FutureTask<String> reader=new FutureTask<>(()->{
            try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] block=new byte[4096];for(int n;(n=input.read(block))!=-1;){
                    if(bytes.size()+n>48*1024)throw fail("benchmark_reply_too_large");bytes.write(block,0,n);
                }return bytes.toString("UTF-8");
            }
        });
        Thread thread=new Thread(reader,"androidworld-model-reply");thread.setDaemon(true);thread.start();
        try{return reader.get(Math.max(1,nanos),TimeUnit.NANOSECONDS);}
        finally{if(!reader.isDone()){fd.close();reader.cancel(true);}}
    }
    @Override public void close()throws Exception{if(!closed){closed=true;lease.close();}}
    private static final class Lease implements AutoCloseable {
        final Object manager,provider;final IBinder token,binder;final java.lang.reflect.Method release,call;
        final AttributionSource caller;boolean closed;
        Lease()throws Exception{
            token=new Binder();caller=new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            Class<?> am=Class.forName("android.app.IActivityManager");manager=android.app.ActivityManager.class.getMethod("getService").invoke(null);
            release=am.getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class);
            call=Class.forName("android.content.IContentProvider").getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class);
            Object holder=am.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
                .invoke(manager,AUTH,0,token,"androidworld-planner");
            if(holder==null)throw fail("benchmark_planner_missing");
            try{provider=Class.forName("android.app.ContentProviderHolder").getField("provider").get(holder);
                binder=((IInterface)provider).asBinder();if(binder==null)throw fail("benchmark_planner_missing");}
            catch(Exception failure){release.invoke(manager,AUTH,token,0);throw failure;}
        }
        Bundle call(String method,String nonce,Bundle data)throws Exception{
            if(closed||!Arrays.asList("identity","decide").contains(method))throw fail("benchmark_planner_operation_denied");
            return (Bundle)call.invoke(provider,caller,AUTH,method,nonce,data);
        }
        @Override public void close()throws Exception{if(!closed){closed=true;release.invoke(manager,AUTH,token,0);}}
    }
}
