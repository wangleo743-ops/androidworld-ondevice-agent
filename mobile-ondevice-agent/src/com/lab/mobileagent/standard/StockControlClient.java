package com.lab.mobileagent.standard;

import android.os.Process;
import com.lab.mobileagent.benchmark.BenchmarkContract;
import com.lab.mobileagent.l3.ComputerControl;
import com.lab.mobileagent.model.Action;
import com.lab.mobileagent.model.Observation;
import java.io.File;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Agent-side proxy. All Android GUI calls happen in the child Control process. */
public final class StockControlClient implements ComputerControl {
    public static final String ROOT = "/data/local/tmp/mobile-ondevice-agent";
    private final BenchmarkContract.TaskSpec task;
    private final String runId, sessionId;
    private final int clientPid;
    private StockMcpConnection connection;
    private JSONObject serverReport;
    private int serverPid;
    private boolean closed;

    public StockControlClient(String run, BenchmarkContract.TaskSpec task, String goal, File directory, long deadlineNanos) throws Exception {
        if (Process.myUid() != 2000 || task == null || run == null || !run.matches("aw-[a-z0-9_-]{1,70}"))
            throw new SecurityException("stock_client_identity_invalid");
        task.validateGoal(goal);
        File expected = new File(ROOT + "/androidworld-runs", run);
        if (directory == null || !directory.isDirectory() || !directory.getCanonicalFile().equals(expected.getAbsoluteFile())
                || !directory.getName().equals(run)) throw new SecurityException("stock_client_directory_invalid");
        String classpath = System.getenv("CLASSPATH");
        if (!(ROOT + "/stock-android-agent.jar").equals(classpath)) throw new SecurityException("stock_client_classpath_invalid");
        this.task = task; runId = run; sessionId = UUID.randomUUID().toString(); clientPid = Process.myPid();
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remaining < 1 || remaining > 1800000) throw new IllegalArgumentException("stock_client_lifetime_invalid");
        JSONObject bootstrap = new JSONObject().put("profile", StockBootstrap.PROFILE).put("run_id", run)
                .put("session_id", sessionId).put("task_name", task.taskName).put("target_package", task.targetPackage)
                .put("goal", goal).put("client_pid", clientPid).put("lifetime_ms", (int)remaining);
        StockBootstrap.parse(bootstrap);
        ProcessBuilder launcher = new ProcessBuilder("/system/bin/app_process", "/system/bin", StockControlMain.class.getName());
        Map<String,String> environment = launcher.environment(); environment.clear();
        for (String name : new String[]{"ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_RUNTIME_ROOT", "ANDROID_I18N_ROOT",
                "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH"}) {
            String value = System.getenv(name); if (value != null) environment.put(name, value);
        }
        environment.put("CLASSPATH", classpath); environment.put("PATH", "/system/bin:/system/xbin");
        java.lang.Process process = launcher.start();
        try {
            connection = new StockMcpConnection(process, bootstrap, deadlineNanos, 60000);
            JSONObject description = connection.callTool("control.session", new JSONObject()).getJSONObject("structuredContent");
            validateReport(description); serverReport = description;
        } catch (Exception failure) {
            if (connection != null) try { connection.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            else process.destroyForcibly();
            throw failure;
        }
    }

    private void active() { if (closed || connection == null) throw new IllegalStateException("stock_control_closed"); }
    @Override public int displayId() { return 0; }

    private void validateReport(JSONObject value) throws Exception {
        if (!runId.equals(value.optString("run_id")) || !sessionId.equals(value.optString("session_id"))
                || !task.targetPackage.equals(value.optString("target_package")) || value.optInt("display_id", -1) != 0
                || value.optInt("client_pid", -1) != clientPid || value.optInt("server_uid", -1) != 2000
                || !(value.opt("server_pid") instanceof Integer) || value.getInt("server_pid") <= 1
                || value.getInt("server_pid") == clientPid || !"stdio_jsonrpc".equals(value.optString("transport")))
            throw new SecurityException("stock_control_report_binding_invalid");
        if (serverPid != 0 && serverPid != value.getInt("server_pid")) throw new SecurityException("stock_control_server_changed");
        serverPid = value.getInt("server_pid");
    }

    @Override public synchronized Observation observe() throws Exception {
        active();
        try {
            JSONObject reply = connection.callTool("gui.observe", new JSONObject());
            JSONObject metadata = reply.getJSONObject("structuredContent");
            validateReport(metadata.getJSONObject("control_session"));
            JSONArray content = reply.getJSONArray("content");
            if (content.length() != 1) throw new SecurityException("stock_observation_content_invalid");
            JSONObject image = content.getJSONObject(0);
            if (!"image".equals(image.optString("type")) || !"image/png".equals(image.optString("mimeType"))
                    || !(image.opt("data") instanceof String)) throw new SecurityException("stock_observation_format_invalid");
            byte[] png = Base64.getDecoder().decode(image.getString("data"));
            int width = metadata.getInt("width"), height = metadata.getInt("height");
            if (png.length < 24 || png[0] != (byte)137 || png[1] != 80 || png[2] != 78 || png[3] != 71
                    || png[4] != 13 || png[5] != 10 || png[6] != 26 || png[7] != 10
                    || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R'
                    || width < 1 || height < 1 || width > 4096 || height > 4096
                    || integer(png,16) != width || integer(png,20) != height || metadata.getInt("display_id") != 0
                    || !task.allowedObservation(metadata.getString("package_name"), metadata.getString("component")))
                throw new SecurityException("stock_observation_binding_invalid");
            long captured = metadata.getLong("captured_nanos"), boundary = metadata.getLong("action_boundary_nanos");
            if (captured <= 0 || captured > System.nanoTime() || boundary < 0 || boundary > captured)
                throw new SecurityException("stock_observation_time_invalid");
            serverReport = metadata.getJSONObject("control_session");
            return new Observation(png,width,height,0,metadata.getString("package_name"),metadata.getString("component"),
                    captured,boundary,metadata.getBoolean("frame_after_action"));
        } catch (Exception failure) { stopOnFailure(failure); throw failure; }
    }
    private static int integer(byte[] b,int at) { return ((b[at]&255)<<24)|((b[at+1]&255)<<16)|((b[at+2]&255)<<8)|(b[at+3]&255); }

    @Override public synchronized void execute(Action action) throws Exception {
        active(); BenchmarkContract.action(task,action);
        try {
            JSONObject result = connection.callTool("gui." + action.name, action.args).getJSONObject("structuredContent");
            validateReport(result.getJSONObject("control_session"));
            if (!"control_returned".equals(result.optString("status"))) throw new IllegalStateException("stock_control_receipt_invalid");
            serverReport = result.getJSONObject("control_session");
        } catch (Exception failure) { stopOnFailure(failure); throw failure; }
    }

    private void stopOnFailure(Exception failure) {
        try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
    }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        try { connection.close(); }
        finally {
            JSONObject transport = connection.report();
            closed = transport.getBoolean("child_exit_confirmed");
            JSONObject finalReport = transport.optJSONObject("final_server_report");
            if (finalReport != null) { validateReport(finalReport); serverReport = finalReport; }
        }
    }

    public synchronized JSONObject report() throws Exception {
        JSONObject transport = connection == null ? new JSONObject() : connection.report();
        return new JSONObject().put("run_id",runId).put("session_id",sessionId).put("task_name",task.taskName)
                .put("target_package",task.targetPackage).put("display_id",0).put("transport","stdio_jsonrpc")
                .put("client_pid",clientPid).put("server_pid",serverPid).put("closed",closed)
                .put("child_exit_confirmed",transport.optBoolean("child_exit_confirmed",false))
                .put("cleanup_confirmed",transport.optJSONObject("final_server_report")!=null
                    &&transport.getJSONObject("final_server_report").optBoolean("cleanup_confirmed",false))
                .put("connection",transport).put("server",serverReport==null?JSONObject.NULL:serverReport)
                .put("boundary","trusted_shell_processes_private_pipes_not_distinct_security_uids")
                .put("timing_definition","server wall times are nested inside client control/observation wall times; do not sum them");
    }
}
