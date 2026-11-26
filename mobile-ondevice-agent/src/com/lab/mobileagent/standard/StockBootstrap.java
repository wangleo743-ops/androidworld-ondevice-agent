package com.lab.mobileagent.standard;

import com.lab.mobileagent.benchmark.BenchmarkContract;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Trusted launcher configuration. This is not an MCP tool and is never model-generated. */
public final class StockBootstrap {
    public static final String PROFILE = "stock_android_mcp_baseline_v1";
    public final String runId, sessionId, goal;
    public final int clientPid, lifetimeMillis;
    public final BenchmarkContract.TaskSpec task;
    private StockBootstrap(JSONObject value) throws Exception {
        Set<String> fields = new HashSet<>();
        for (Iterator<String> it = value.keys(); it.hasNext();) fields.add(it.next());
        if (!fields.equals(new HashSet<>(Arrays.asList("profile", "run_id", "session_id", "task_name", "target_package", "goal", "client_pid", "lifetime_ms"))))
            throw new SecurityException("stock_bootstrap_fields_invalid");
        if (!PROFILE.equals(value.getString("profile"))
                || !value.getString("run_id").matches("aw-[a-z0-9_-]{1,70}")
                || !value.getString("session_id").matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || !(value.get("client_pid") instanceof Integer) || value.getInt("client_pid") <= 1
                || !(value.get("lifetime_ms") instanceof Integer) || value.getInt("lifetime_ms") < 1
                || value.getInt("lifetime_ms") > 1800000) throw new SecurityException("stock_bootstrap_binding_invalid");
        task = BenchmarkContract.task(value.getString("task_name"));
        task.validateGoal(value.getString("goal"));
        if (!task.targetPackage.equals(value.getString("target_package"))) throw new SecurityException("stock_bootstrap_target_invalid");
        runId = value.getString("run_id"); sessionId = value.getString("session_id"); goal = value.getString("goal");
        clientPid = value.getInt("client_pid"); lifetimeMillis = value.getInt("lifetime_ms");
    }
    public static StockBootstrap parse(JSONObject value) throws Exception { return new StockBootstrap(value); }

    public static boolean parentCommandMatches(byte[] command,String run) {
        if(command==null||command.length==0||command.length>8192||run==null)return false;
        List<String> tokens=new ArrayList<>();int start=0;
        for(int i=0;i<command.length;i++)if(command[i]==0){
            if(i>start)tokens.add(new String(command,start,i-start,StandardCharsets.UTF_8));start=i+1;
        }
        if(start!=command.length||tokens.size()<3)return false;
        int end=tokens.size();
        return "com.lab.mobileagent.benchmark.AndroidWorldMain".equals(tokens.get(end-3))
                &&"run".equals(tokens.get(end-2))&&run.equals(tokens.get(end-1));
    }
}
