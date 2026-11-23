package com.lab.mobileagent.benchmark;

import com.lab.mobileagent.l1.Decision;
import com.lab.mobileagent.l1.ModelConnection;
import com.lab.mobileagent.model.Action;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** A narrow, separately versioned experiment contract, not an extension of weather permissions. */
public final class BenchmarkContract {
    public static final String PROFILE = "androidworld_api33_isolated_baseline_v2";
    /** Explicit opt-in; legacy experiments and their profile name remain unchanged. */
    public static final String STOCK_PROFILE = "stock_android_mcp_baseline_v1";
    public static final String CATALOG_VERSION = "androidworld-fixed-four-tasks-v2";
    // Legacy constants retained for the original stopwatch fixture; runtime uses TaskSpec.
    public static final String TASK = "ClockStopWatchRunning";
    public static final String TARGET = "com.google.android.deskclock";
    public static final String GOAL = "Run the stopwatch.";
    private BenchmarkContract() {}

    public static boolean isStockProfile(String profile) { return STOCK_PROFILE.equals(profile); }
    public static String agentJar(String profile) {
        if (PROFILE.equals(profile)) return "androidworld-agent.jar";
        if (STOCK_PROFILE.equals(profile)) return "stock-android-agent.jar";
        throw bad("benchmark_profile_not_allowed");
    }

    public static final class TaskSpec {
        public final String taskName,targetPackage;
        private final String goal;
        private static final Pattern TIMER=Pattern.compile("Create a timer with (0|[1-9][0-9]?) hours, (0|[1-9][0-9]?) minutes, and (0|[1-9][0-9]?) seconds\\. Do not start the timer\\.");
        private TaskSpec(String name,String target,String goal){this.taskName=name;this.targetPackage=target;this.goal=goal;}
        public void validateGoal(String value){
            if(value==null)throw bad("benchmark_goal_invalid");
            if(goal!=null){if(!goal.equals(value))throw bad("benchmark_goal_invalid");return;}
            Matcher timer=TIMER.matcher(value);
            if(!timer.matches()||Integer.parseInt(timer.group(1))>23||Integer.parseInt(timer.group(2))>59
                    ||Integer.parseInt(timer.group(3))>59)throw bad("benchmark_goal_invalid");
        }
        public boolean allowedObservation(String pkg,String component){
            if(pkg==null||component==null||!component.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+"))return false;
            if(!component.startsWith(pkg+"/"))return false;
            if(targetPackage.equals(pkg)||"com.android.launcher3".equals(pkg)||"com.google.android.apps.nexuslauncher".equals(pkg))return true;
            return allowedTransient(pkg,component);
        }
        public boolean allowedTransient(String pkg,String component){
            return "SystemBrightnessMax".equals(taskName)&&"com.android.systemui".equals(pkg)
                &&("com.android.systemui/com.android.systemui.settings.brightness.BrightnessDialog".equals(component)
                   ||"com.android.systemui/.settings.brightness.BrightnessDialog".equals(component));
        }
        public boolean targetUidAllowed(int uid,boolean systemApp){
            if("com.android.settings".equals(targetPackage))return uid==1000&&systemApp;
            return uid>=10000&&uid<=19999;
        }
    }

    public static TaskSpec task(String name){
        if(TASK.equals(name))return new TaskSpec(TASK,TARGET,GOAL);
        if("ClockTimerEntry".equals(name))return new TaskSpec(name,TARGET,null);
        if("SystemBrightnessMax".equals(name))return new TaskSpec(name,"com.android.settings","Turn brightness to the max value.");
        if("CameraTakePhoto".equals(name))return new TaskSpec(name,"com.android.camera2","Take one photo.");
        throw bad("benchmark_task_not_allowed");
    }

    static void exact(JSONObject object, String... fields) {
        Set<String> found = new HashSet<>();
        for (Iterator<String> it=object.keys(); it.hasNext();) found.add(it.next());
        if (!found.equals(new HashSet<>(Arrays.asList(fields)))) throw bad("benchmark_unexpected_fields");
    }
    static IllegalArgumentException bad(String code) { return new IllegalArgumentException(code); }
    public static boolean modelDone(String kind) { return "finish".equals(kind)||"blocked".equals(kind); }
    public static double agentScore(double raw,boolean done) {
        if(!Double.isFinite(raw)||raw<0||raw>1)throw bad("benchmark_score_invalid");
        return done?raw:0.0;
    }
    static boolean seed(JSONObject c) throws Exception {
        Object value=c.get("seed");
        return (value instanceof Integer||value instanceof Long)&&c.getLong("seed")>=0&&c.getLong("seed")<=4294967295L;
    }
    public static JSONObject validate(JSONObject c) throws Exception {
        exact(c,"profile","run_id","task_name","target_package","goal","seed","max_steps","model","effort","timeout_seconds","api_key","responses_endpoint","allow_http");
        if (!(c.get("allow_http") instanceof Boolean)) throw bad("benchmark_allow_http_invalid");
        ModelConnection.endpoint(c.getString("responses_endpoint"),c.getBoolean("allow_http"));
        TaskSpec spec=task(c.getString("task_name"));spec.validateGoal(c.getString("goal"));
        if (!(PROFILE.equals(c.getString("profile")) || STOCK_PROFILE.equals(c.getString("profile")))
                || !spec.targetPackage.equals(c.getString("target_package"))
                || !c.getString("run_id").matches("aw-[a-z0-9_-]{1,70}")
                || !seed(c)
                || !(c.get("max_steps") instanceof Integer) || c.getInt("max_steps")<1 || c.getInt("max_steps")>100
                || !(c.get("timeout_seconds") instanceof Integer) || c.getInt("timeout_seconds")<30 || c.getInt("timeout_seconds")>1800
                || !c.getString("model").matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                || !ModelConnection.validEffort(c.getString("effort"))
                || !c.getString("api_key").matches("[!-~]{1,4096}")) throw bad("benchmark_config_invalid");
        return c;
    }
    public static void action(Action a) throws Exception {
        action(task(TASK),a);
    }
    public static void action(TaskSpec spec,Action a) throws Exception {
        if(spec==null)throw bad("benchmark_task_required");
        if (a==null) throw bad("benchmark_action_required");
        // Reuse the strict existing model contract, then apply the narrower benchmark scope.
        Decision parsed=Decision.parse(new JSONObject().put("kind","action").put("action",a.json())
                .put("answer",JSONObject.NULL).toString(),false);
        if (!Arrays.asList("open_app","click","swipe","go_back","wait","take_screenshot").contains(parsed.action.name))
            throw bad("benchmark_action_not_allowed");
    }
    static JSONObject command(JSONObject c,String run,int sequence) throws Exception {
        return command(c,run,sequence,task(TASK));
    }
    static JSONObject command(JSONObject c,String run,int sequence,TaskSpec spec) throws Exception {
        String op=c.getString("command");
        if ("evaluate".equals(op)) {
            exact(c,"command","run_id","sequence","task_name","seed","score","evaluator");
            if (!spec.taskName.equals(c.getString("task_name")) || !seed(c) || !(c.get("score") instanceof Number)
                    || !Double.isFinite(c.getDouble("score")) || c.getDouble("score")<0 || c.getDouble("score")>1
                    || !"android_world.task.is_successful".equals(c.getString("evaluator"))) throw bad("benchmark_evaluation_invalid");
        } else {
            exact(c,"command","run_id","sequence");
            if (!Arrays.asList("advance","stop").contains(op)) throw bad("benchmark_command_invalid");
        }
        if (!run.equals(c.getString("run_id")) || !(c.get("sequence") instanceof Integer)
                || c.getInt("sequence")!=sequence) throw bad("benchmark_command_binding_mismatch");
        return c;
    }
}
