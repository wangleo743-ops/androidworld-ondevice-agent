package com.lab.mobileagent.standard;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import com.lab.mobileagent.benchmark.AndroidWorldControl;
import com.lab.mobileagent.benchmark.BenchmarkContract;
import com.lab.mobileagent.runtime.AndroidRuntime;
import com.lab.mobileagent.model.Action;
import com.lab.mobileagent.model.Observation;
import com.lab.mobileagent.platform.BaselineSession;
import com.lab.mobileagent.platform.FileExecutionLog;
import com.lab.mobileagent.platform.SessionBinding;
import com.lab.mobileagent.service.BenchmarkResources;
import com.lab.mobileagent.service.GuiMcpService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** A per-task shell Control service over inherited pipes; not a privileged MobiClaw system service. */
public final class StockControlMain {
    private StockControlMain() {}
    public static void main(String[] args) {
        try {
            String emulator = (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class)
                    .invoke(null,"ro.kernel.qemu");
            if (args.length != 0 || Process.myUid() != 2000 || Build.VERSION.SDK_INT != 33 || !"1".equals(emulator))
                throw new SecurityException("stock_control_environment_denied");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            AndroidRuntime.initializeAndroidContextOnMainThread();
            new Handler(Looper.getMainLooper()).post(() -> {
                Thread worker = new Thread(() -> {
                    int exit = 2;
                    try { new Server().run(); exit = 0; }
                    catch (Exception error) { System.err.println("stock_control_failed:" + error.getClass().getSimpleName()); }
                    System.exit(exit);
                }, "stock-control-service");
                worker.start();
            });
            Looper.loop();
        } catch (Exception error) {
            System.err.println("stock_control_start_failed:" + error.getClass().getSimpleName()); System.exit(2);
        }
    }

    private static final class Server {
        final ArrayBlockingQueue<String> incoming = new ArrayBlockingQueue<>(2);
        final AtomicReference<String> disconnected = new AtomicReference<>();
        final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"stock-control-write");t.setDaemon(true);return t; });
        final long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(30), observationNanos, executionNanos;
        StockBootstrap config; File directory; AndroidWorldControl device; BaselineSession session; GuiMcpService mcp;
        boolean cleanupConfirmed; String exitReason = "initializing";
        int admitted, rechecks;

        void run() throws Exception {
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = StockWire.read(System.in, StockWire.MAX_REQUEST_BYTES)) != null) {
                        if (!incoming.offer(line)) throw new IllegalStateException("stock_request_queue_full");
                    }
                    disconnected.compareAndSet(null,"client_eof");
                } catch (Exception failure) { disconnected.compareAndSet(null,"invalid_client_pipe"); }
            }, "stock-control-read");
            reader.setDaemon(true); reader.start();
            try {
                config = StockBootstrap.parse(StockWire.object(next()));
                if (Os.getppid() != config.clientPid) throw new SecurityException("stock_control_parent_mismatch");
                verifyParent(config.clientPid,config.runId);
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.lifetimeMillis);
                directory = new File(StockControlClient.ROOT + "/androidworld-runs",config.runId);
                if (!directory.isDirectory() || !directory.getCanonicalFile().equals(directory.getAbsoluteFile())
                        || new File(directory,"stock-control-lifecycle.json").exists())
                    throw new SecurityException("stock_control_fresh_run_required");
                device = new AndroidWorldControl(AndroidRuntime.executionContext(),config.task);
                SessionBinding binding = SessionBinding.stockEmulator(config.sessionId,config.runId,config.goal,
                        config.task.targetPackage,device.report().getInt("target_uid"));
                session = BaselineSession.open(device,binding,new BaselineSession.Scope() {
                    public void check(String operation,Action action) throws Exception { scope(operation,action); }
                    public void checkObservation(Observation observation) {
                        if (observation.displayId != 0 || !config.task.allowedObservation(observation.packageName,observation.component))
                            throw new SecurityException("stock_observation_scope_denied");
                    }
                },new FileExecutionLog(new File(directory,"stock-control-events.jsonl")));
                mcp = new GuiMcpService(BenchmarkResources.isolatedEmulator(config.runId),(operation,action,phase) -> {
                    scope(operation,action);
                    if ("admission".equals(phase)) admitted++; else if ("dispatch".equals(phase)) rechecks++;
                },(operation,action) -> execute(operation,action),(operation,action) -> {});
                exitReason="active"; lifecycle("active");
                for (;;) {
                    JSONObject request=StockWire.object(next());
                    JSONObject response=mcp.handle(request.toString());
                    boolean close=false;
                    if (response != null && "tools/list".equals(request.optString("method")) && response.has("result")) {
                        JSONArray all=response.getJSONObject("result").getJSONArray("tools"),tools=new JSONArray();
                        for(int i=0;i<all.length();i++){
                            JSONObject tool=all.getJSONObject(i);
                            if(Arrays.asList("gui.open_app","gui.click","gui.swipe","gui.go_back","gui.wait","gui.take_screenshot","gui.observe")
                                    .contains(tool.optString("name")))tools.put(tool);
                        }
                        response.getJSONObject("result").put("tools",tools);
                        tools.put(tool("control.session","Read this connection's trusted task binding and measured service timing."));
                        tools.put(tool("control.capabilities","Read implemented and reserved platform capabilities."));
                        tools.put(tool("control.close","Close this task connection without resetting its target application."));
                    } else if (extension(request,response)) {
                        String name=request.getJSONObject("params").getString("name");
                        JSONObject data;
                        if ("control.close".equals(name)) {
                            exitReason="client_closed"; closeResources(); lifecycle("closed"); close=true; data=report();
                        } else if ("control.capabilities".equals(name)) data=session.capabilities().json();
                        else data=report();
                        response=toolResult(request.get("id"),data);
                    }
                    if (response != null) send(response);
                    if (close) break;
                }
            } catch (Exception failure) {
                exitReason=disconnected.get()!=null?disconnected.get():System.nanoTime()>=deadline?"deadline":"service_failure";
                throw failure;
            } finally {
                try { closeResources(); }
                finally {
                    try { if(directory!=null && config!=null) lifecycle("closed"); }
                    finally {writer.shutdownNow();try{System.in.close();}catch(Exception ignored){} }
                }
            }
        }

        void scope(String operation,Action action) throws Exception {
            if (disconnected.get()!=null || System.nanoTime()>=deadline || cleanupConfirmed)
                throw new IllegalStateException("stock_session_inactive");
            if ("observe".equals(operation) && action==null) return;
            if (action==null || !operation.equals(action.name)) throw new SecurityException("stock_operation_not_supported");
            BenchmarkContract.action(config.task,action);
        }

        JSONObject execute(String operation,Action action) throws Exception {
            if ("observe".equals(operation) && action==null) {
                long began=System.nanoTime(); Observation observation;
                try { observation=session.observe(); } finally {observationNanos+=System.nanoTime()-began;}
                return new JSONObject().put("mimeType","image/png").put("data",Base64.getEncoder().encodeToString(observation.png))
                        .put("width",observation.width).put("height",observation.height).put("display_id",observation.displayId)
                        .put("package_name",observation.packageName).put("component",observation.component)
                        .put("captured_nanos",observation.capturedNanos).put("action_boundary_nanos",observation.actionBoundaryNanos)
                        .put("frame_after_action",observation.frameAfterAction).put("control_session",report());
            }
            long began=System.nanoTime();
            try {session.execute(operation,action);} finally {executionNanos+=System.nanoTime()-began;}
            return new JSONObject().put("status","control_returned").put("control_session",report());
        }

        String next() throws Exception {
            for (;;) {
                if(disconnected.get()!=null)throw new IllegalStateException("stock_client_disconnected");
                if(System.nanoTime()>=deadline)throw new IllegalStateException("stock_session_deadline");
                String line=incoming.poll(100,TimeUnit.MILLISECONDS);
                if(line!=null)return line;
            }
        }

        void send(JSONObject response) throws Exception {
            Future<?> output=writer.submit(() -> {
                try {StockWire.write(System.out,response,StockWire.MAX_FRAME_BYTES);}
                catch(Exception failure){throw new IllegalStateException("stock_output_failed",failure);}
            });
            long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
            try {output.get(Math.max(1,Math.min(10000,remaining)),TimeUnit.MILLISECONDS);}
            catch(Exception failure){output.cancel(true);throw new IllegalStateException("stock_output_timeout_or_failure");}
        }

        void closeResources() throws Exception {
            if(cleanupConfirmed)return;
            Exception failure=null;
            try {if(mcp!=null)mcp.close();}catch(Exception error){failure=error;}
            try {if(session!=null)session.close();else if(device!=null)device.close();}
            catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            cleanupConfirmed=failure==null;
            if(failure!=null)throw failure;
        }

        JSONObject report() throws Exception {
            return new JSONObject().put("run_id",config.runId).put("session_id",config.sessionId)
                    .put("task_name",config.task.taskName).put("target_package",config.task.targetPackage).put("display_id",0)
                    .put("client_pid",config.clientPid).put("server_pid",Process.myPid()).put("server_uid",Process.myUid())
                    .put("transport","stdio_jsonrpc").put("execution_location","android_emulator_control_child")
                    .put("formal_user_grants",false).put("closed",cleanupConfirmed).put("cleanup_confirmed",cleanupConfirmed)
                    .put("exit_reason",exitReason).put("session",session==null?JSONObject.NULL:session.report())
                    .put("device",device==null?JSONObject.NULL:device.report())
                    .put("queue",mcp==null?JSONObject.NULL:mcp.report()).put("admission_checks",admitted).put("dispatch_checks",rechecks)
                    .put("timing",new JSONObject().put("server_wall_seconds",(System.nanoTime()-started)/1e9)
                        .put("execution_seconds",executionNanos/1e9).put("observation_seconds",observationNanos/1e9)
                        .put("definition","server execution/observation includes validation and event logging; nested in caller wall time"));
        }

        void lifecycle(String state) throws Exception {
            File target=new File(directory,"stock-control-lifecycle.json"),temp=new File(directory,".stock-control-"+UUID.randomUUID()+".tmp");
            JSONObject value=report().put("state",state).put("pid",Process.myPid()).put("ppid",Os.getppid())
                    .put("control_closed",cleanupConfirmed).put("process_exit_must_be_verified_separately",true);
            Files.write(temp.toPath(),(value.toString(2)+"\n").getBytes(StandardCharsets.UTF_8));
            if(!temp.renameTo(target))throw new IllegalStateException("stock_control_lifecycle_publish_failed");
        }

        static void verifyParent(int pid,String run) throws Exception {
            byte[] bytes=Files.readAllBytes(new File("/proc/"+pid+"/cmdline").toPath());
            // Identity comes from the actual inherited parent relationship, never MCP request fields.
            if(!StockBootstrap.parentCommandMatches(bytes,run))
                throw new SecurityException("stock_parent_scope_invalid");
            String status=new String(Files.readAllBytes(new File("/proc/"+pid+"/status").toPath()),StandardCharsets.UTF_8);
            java.util.regex.Matcher uid=java.util.regex.Pattern.compile("(?m)^Uid:\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*$").matcher(status);
            if(!uid.find()||!"2000".equals(uid.group(1))||!"2000".equals(uid.group(2))
                    ||!"2000".equals(uid.group(3))||!"2000".equals(uid.group(4)))throw new SecurityException("stock_parent_uid_invalid");
        }

        static boolean extension(JSONObject request,JSONObject response) throws Exception {
            if(response==null || !"tools/call".equals(request.optString("method")))return false;
            JSONObject error=response.optJSONObject("error"),params=request.optJSONObject("params");
            if(error==null || error.optInt("code")!=-32602 || !"mcp_invalid_tool".equals(error.optString("message")) || params==null)return false;
            if(!Arrays.asList("control.session","control.capabilities","control.close").contains(params.optString("name")))return false;
            Set<String> fields=new HashSet<>();for(Iterator<String> i=params.keys();i.hasNext();)fields.add(i.next());
            if(!fields.equals(new HashSet<>(Arrays.asList("name","arguments"))))return false;
            return params.optJSONObject("arguments")!=null && params.getJSONObject("arguments").length()==0;
        }
        static JSONObject tool(String name,String description) throws Exception {
            return new JSONObject().put("name",name).put("description",description).put("inputSchema",new JSONObject()
                    .put("type","object").put("properties",new JSONObject()).put("additionalProperties",false));
        }
        static JSONObject toolResult(Object id,JSONObject data) throws Exception {
            return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("isError",false)
                    .put("structuredContent",data).put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",data.toString()))));
        }
    }
}
