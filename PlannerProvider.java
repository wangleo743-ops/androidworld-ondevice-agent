package com.lab.androidworldplanner;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SharedMemory;
import com.lab.mobileagent.benchmark.BenchmarkContract;
import com.lab.mobileagent.l1.PlannerMessages;
import com.lab.mobileagent.l1.ModelConnection;
import com.lab.mobileagent.l1.ResponsesClient;
import com.lab.mobileagent.l1.StrictJson;
import com.lab.mobileagent.model.Observation;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Benchmark-only model worker: ordinary app UID, INTERNET only, no device-control capability. */
public final class PlannerProvider extends ContentProvider {
    private final AtomicBoolean busy=new AtomicBoolean();
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String nonce,Bundle data){
        int caller=Binder.getCallingUid();
        if(caller!=2000||Process.myUid()<10000||Process.myUid()==caller)
            throw new SecurityException("benchmark_planner_caller_denied");
        if(nonce==null||!nonce.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("invalid_challenge");
        long saved=Binder.clearCallingIdentity();
        try{
            if(data==null)throw new SecurityException("benchmark_task_binding_required");
            BenchmarkContract.TaskSpec spec=BenchmarkContract.task(data.getString("task_name"));
            if(!spec.targetPackage.equals(data.getString("target_package")))throw new SecurityException("benchmark_target_mismatch");
            Bundle reply=new Bundle();
            reply.putInt("uid",Process.myUid());reply.putInt("pid",Process.myPid());reply.putInt("caller_uid",caller);
            reply.putString("challenge",nonce);reply.putString("target",spec.targetPackage);reply.putString("task_name",spec.taskName);reply.putInt("protocol",2);
            if("identity".equals(method)&&data.keySet().equals(new HashSet<>(Arrays.asList("task_name","target_package")))){
                reply.putBoolean("inject_events_denied",getContext().checkSelfPermission("android.permission.INJECT_EVENTS")!=PackageManager.PERMISSION_GRANTED);
                reply.putBoolean("dump_denied",getContext().checkSelfPermission("android.permission.DUMP")!=PackageManager.PERMISSION_GRANTED);
                return reply;
            }
            if(!"decide".equals(method)||data==null||!data.keySet().equals(new HashSet<>(Arrays.asList(
                    "task","history","key","model","effort","dead","png","reply","width","height","display",
                    "captured","observed","boundary","new_frame","package","component","task_name","target_package","responses_endpoint","allow_http"))))throw new SecurityException("benchmark_planner_operation_denied");
            SharedMemory image=data.getParcelable("png",SharedMemory.class);
            ParcelFileDescriptor sink=data.getParcelable("reply",ParcelFileDescriptor.class);
            if(image==null||sink==null)throw new IllegalArgumentException("invalid_transport");
            boolean claimed=false;
            try{
                String task=data.getString("task"),history=data.getString("history"),key=data.getString("key");
                spec.validateGoal(task);
                if (!(data.get("allow_http") instanceof Boolean)) throw new IllegalArgumentException("invalid_model_http_setting");
                ModelConnection.endpoint(data.getString("responses_endpoint"),data.getBoolean("allow_http"));
                if(task==null||task.isEmpty()||task.length()>4000||history==null||history.length()>131072
                        ||key==null||!key.matches("[!-~]{1,4096}")||image.getSize()<24||image.getSize()>12*1024*1024
                        ||data.getInt("display",-1)!=0||data.getInt("width")<1||data.getInt("height")<1
                        ||data.getInt("width")>8192||data.getInt("height")>8192||data.getLong("dead")<=System.nanoTime()
                        ||!spec.allowedObservation(data.getString("package"),data.getString("component"))
                        ||data.getString("component")==null||data.getString("component").length()>512)
                    throw new IllegalArgumentException("invalid_benchmark_model_input");
                if(!busy.compareAndSet(false,true))throw new IllegalStateException("planner_busy");
                claimed=true;
                new Thread(()->decide(data,image,sink,nonce,spec),"androidworld-model-decision").start();
                return reply;
            }catch(RuntimeException|Error failure){
                if(claimed)busy.set(false);
                image.close();try{sink.close();}catch(Exception ignored){}
                throw failure;
            }
        }finally{Binder.restoreCallingIdentity(saved);}
    }
    private void decide(Bundle data,SharedMemory image,ParcelFileDescriptor sink,String nonce,BenchmarkContract.TaskSpec spec){
        try(SharedMemory held=image;OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(sink)){
            JSONObject reply=new JSONObject().put("uid",Process.myUid()).put("pid",Process.myPid())
                .put("challenge",nonce).put("task_name",spec.taskName).put("target",spec.targetPackage).put("source","live_model");
            try{
                ByteBuffer mapped=held.mapReadOnly();byte[] png;
                try{png=new byte[mapped.remaining()];mapped.get(png);}finally{SharedMemory.unmap(mapped);}
                String history=data.getString("history");StrictJson.validate(history);
                long deadline=Math.min(data.getLong("dead"),System.nanoTime()+TimeUnit.SECONDS.toNanos(120));
                Observation frame=new Observation(png,data.getInt("width"),data.getInt("height"),0,data.getString("package"),data.getString("component"),
                    data.getLong("captured"),data.getLong("boundary"),data.getLong("observed"),data.getBoolean("new_frame"));
                ResponsesClient client=ResponsesClient.androidWorldTask(data.getString("key"),data.getString("model"),
                    data.getString("effort"),deadline,spec.taskName,spec.targetPackage,
                    data.getString("responses_endpoint"),data.getBoolean("allow_http"));
                reply.put("decision",PlannerMessages.reply(client.decide(data.getString("task"),frame,new JSONArray(history))));
            }catch(Exception failure){
                String message=failure.getMessage();
                reply.put("error",message!=null&&message.matches("model_[a-z0-9_]{1,60}")?message:"benchmark_planner_failed");
            }finally{data.clear();}
            output.write(reply.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){/* Do not log task data or credentials. */}
        finally{busy.set(false);}
    }
    @Override public String getType(Uri uri){return null;}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new SecurityException("denied");}
    @Override public Uri insert(Uri u,ContentValues v){throw new SecurityException("denied");}
    @Override public int update(Uri u,ContentValues v,String w,String[] a){throw new SecurityException("denied");}
    @Override public int delete(Uri u,String w,String[] a){throw new SecurityException("denied");}
}
