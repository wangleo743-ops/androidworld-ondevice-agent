package com.lab.mobileagent.benchmark;

import com.lab.mobileagent.l1.Decision;
import com.lab.mobileagent.model.Action;
import org.json.JSONObject;

/** No model, device or filesystem mutation. Runs with Android org.json or its JVM implementation. */
public final class BenchmarkContractChecks {
    private interface Work{void run()throws Exception;}
    private static int checks;
    private static void require(boolean condition){if(!condition)throw new AssertionError("benchmark_contract_regression");checks++;}
    private static void deny(Work call)throws Exception{boolean denied=false;try{call.run();}catch(IllegalArgumentException|SecurityException expected){denied=true;}if(!denied)throw new AssertionError("unexpected_allow");checks++;}
    private static JSONObject config()throws Exception{return new JSONObject().put("profile",BenchmarkContract.PROFILE)
        .put("run_id","aw-contract-test").put("task_name",BenchmarkContract.TASK).put("target_package",BenchmarkContract.TARGET)
        .put("goal",BenchmarkContract.GOAL).put("seed",0).put("max_steps",10).put("model","test-model").put("effort","low")
        .put("timeout_seconds",120).put("api_key","nonsecret-test-placeholder")
        .put("responses_endpoint","https://api.example.test/v1/responses").put("allow_http",false);}
    private static Action action(String name,JSONObject args)throws Exception{return Decision.parse(new JSONObject().put("kind","action")
        .put("action",new JSONObject().put("name",name).put("args",args)).put("answer",JSONObject.NULL).toString()).action;}
    private static JSONObject configFor(String name,String goal)throws Exception{
        BenchmarkContract.TaskSpec spec=BenchmarkContract.task(name);
        return config().put("task_name",name).put("target_package",spec.targetPackage).put("goal",goal);
    }
    public static void main(String[] ignored)throws Exception{
        BenchmarkContract.validate(config());checks++;
        BenchmarkContract.validate(config().put("effort","default"));checks++;
        BenchmarkContract.validate(config().put("effort","none"));checks++;
        BenchmarkContract.validate(config().put("responses_endpoint","http://127.0.0.1:8080/responses").put("allow_http",true));checks++;
        deny(()->BenchmarkContract.validate(config().put("responses_endpoint","http://127.0.0.1:8080/responses")));
        deny(()->BenchmarkContract.validate(config().put("allow_http","true")));
        deny(()->BenchmarkContract.validate(config().put("responses_endpoint","https://api.example.test/responses?key=forbidden")));
        BenchmarkContract.validate(config().put("profile",BenchmarkContract.STOCK_PROFILE));checks++;
        require("androidworld-agent.jar".equals(BenchmarkContract.agentJar(BenchmarkContract.PROFILE)));
        require("stock-android-agent.jar".equals(BenchmarkContract.agentJar(BenchmarkContract.STOCK_PROFILE)));
        deny(()->BenchmarkContract.agentJar("mobiclaw"));
        deny(()->BenchmarkContract.validate(config().put("profile","mobiclaw")));
        deny(()->BenchmarkContract.validate(config().put("profile",BenchmarkContract.STOCK_PROFILE).put("display_id",1)));
        deny(()->BenchmarkContract.validate(config().put("profile",BenchmarkContract.STOCK_PROFILE).put("target_package","com.android.settings")));
        BenchmarkContract.validate(config().put("seed",3429360255L));checks++;
        BenchmarkContract.validate(config().put("seed",4294967295L));checks++;
        BenchmarkContract.validate(configFor("ClockTimerEntry","Create a timer with 0 hours, 16 minutes, and 35 seconds. Do not start the timer."));checks++;
        BenchmarkContract.validate(configFor("SystemBrightnessMax","Turn brightness to the max value."));checks++;
        BenchmarkContract.validate(configFor("CameraTakePhoto","Take one photo."));checks++;
        deny(()->BenchmarkContract.validate(config().put("profile","androidworld_api33_isolated_baseline_v1")));
        deny(()->BenchmarkContract.task("SystemBrightnessMin"));
        deny(()->BenchmarkContract.task("ClockStopWatchPausedVerify"));
        deny(()->BenchmarkContract.validate(configFor("CameraTakePhoto","Take one video.")));
        deny(()->BenchmarkContract.validate(configFor("CameraTakePhoto","Take one photo.").put("target_package",BenchmarkContract.TARGET)));
        deny(()->BenchmarkContract.validate(configFor("SystemBrightnessMax","Turn brightness to the min value.")));
        BenchmarkContract.TaskSpec timer=BenchmarkContract.task("ClockTimerEntry");
        timer.validateGoal("Create a timer with 23 hours, 59 minutes, and 59 seconds. Do not start the timer.");checks++;
        timer.validateGoal("Create a timer with 0 hours, 0 minutes, and 0 seconds. Do not start the timer.");checks++;
        for(String goal:new String[]{"Create a timer with 24 hours, 0 minutes, and 0 seconds. Do not start the timer.",
                "Create a timer with 0 hours, 60 minutes, and 0 seconds. Do not start the timer.",
                "Create a timer with 0 hours, 0 minutes, and 60 seconds. Do not start the timer.",
                "Create a timer with -1 hours, 0 minutes, and 0 seconds. Do not start the timer.",
                "Create a timer with 00 hours, 0 minutes, and 0 seconds. Do not start the timer.",
                "Create a timer with 0 hours, 1 minutes, and 2 seconds. Start the timer.",
                "Create a timer with 0 hours, 1 minutes, and 2 seconds. Do not start the timer. Then delete data."}){
            deny(()->timer.validateGoal(goal));
        }
        BenchmarkContract.TaskSpec brightness=BenchmarkContract.task("SystemBrightnessMax");
        BenchmarkContract.TaskSpec clock=BenchmarkContract.task(BenchmarkContract.TASK);
        BenchmarkContract.TaskSpec camera=BenchmarkContract.task("CameraTakePhoto");
        require(brightness.allowedObservation("com.android.settings","com.android.settings/.Settings"));
        require(brightness.allowedObservation("com.android.systemui","com.android.systemui/.settings.brightness.BrightnessDialog"));
        require(brightness.allowedObservation("com.android.systemui","com.android.systemui/com.android.systemui.settings.brightness.BrightnessDialog"));
        require(!brightness.allowedObservation("com.android.systemui","com.android.systemui/.SomeOtherDialog"));
        require(!clock.allowedObservation("com.android.systemui","com.android.systemui/.settings.brightness.BrightnessDialog"));
        require(!camera.allowedObservation("com.android.settings","com.android.settings/.Settings"));
        require(!brightness.allowedObservation("com.android.settings","com.android.systemui/.Settings"));
        require(!camera.allowedObservation("com.android.camera2","com.android.camera2/../Settings"));
        require(clock.allowedObservation("com.android.launcher3","com.android.launcher3/.Launcher"));
        require(camera.allowedObservation("com.android.camera2","com.android.camera2/com.android.camera.CameraLauncher"));
        require(brightness.targetUidAllowed(1000,true));
        require(!brightness.targetUidAllowed(1000,false));
        require(!brightness.targetUidAllowed(10001,true));
        require(!clock.targetUidAllowed(1000,true));
        require(camera.targetUidAllowed(10138,true));
        for(String[] change:new String[][]{{"profile","execution-service"},{"run_id","../weather"},{"task_name","ContactsAddContact"},
                {"target_package","com.android.settings"},{"model","model;sh"},{"effort","max"}}){
            deny(()->BenchmarkContract.validate(config().put(change[0],change[1])));
        }
        deny(()->BenchmarkContract.validate(config().put("shell","id")));
        deny(()->BenchmarkContract.validate(config().put("goal","Change the system settings")));
        deny(()->BenchmarkContract.validate(config().put("max_steps",0)));
        deny(()->BenchmarkContract.validate(config().put("max_steps",101)));
        deny(()->BenchmarkContract.validate(config().put("max_steps",1.0)));
        deny(()->BenchmarkContract.validate(config().put("seed",-1)));
        deny(()->BenchmarkContract.validate(config().put("seed",4294967296L)));
        deny(()->BenchmarkContract.validate(config().put("seed",1.0)));
        deny(()->BenchmarkContract.validate(config().put("api_key","bad\nkey")));
        BenchmarkContract.action(action("open_app",new JSONObject().put("app_name","Browser")));checks++;
        BenchmarkContract.action(action("click",new JSONObject().put("x",0).put("y",999)));checks++;
        BenchmarkContract.action(action("go_back",new JSONObject()));checks++;
        deny(()->BenchmarkContract.action(action("type",new JSONObject().put("text","anything").put("press_enter",false))));
        deny(()->BenchmarkContract.action(action("press_key",new JSONObject().put("key","HOME"))));
        deny(()->BenchmarkContract.action(action("click",new JSONObject().put("x",1000).put("y",1))));
        deny(()->BenchmarkContract.action(action("open_app",new JSONObject().put("app_name","Settings"))));
        JSONObject next=new JSONObject().put("command","advance").put("run_id","aw-contract-test").put("sequence",1);
        BenchmarkContract.command(next,"aw-contract-test",1);checks++;
        deny(()->BenchmarkContract.command(next,"aw-contract-test",2));
        deny(()->BenchmarkContract.command(next,"aw-another-run",1));
        deny(()->BenchmarkContract.command(new JSONObject(next.toString()).put("action","tap"),"aw-contract-test",1));
        JSONObject score=new JSONObject().put("command","evaluate").put("run_id","aw-contract-test").put("sequence",2)
            .put("task_name",BenchmarkContract.TASK).put("seed",0).put("score",1.0).put("evaluator","android_world.task.is_successful");
        BenchmarkContract.command(score,"aw-contract-test",2);checks++;
        JSONObject cameraScore=new JSONObject(score.toString()).put("task_name","CameraTakePhoto");
        BenchmarkContract.command(cameraScore,"aw-contract-test",2,camera);checks++;
        deny(()->BenchmarkContract.command(cameraScore,"aw-contract-test",2,brightness));
        deny(()->BenchmarkContract.command(score,"aw-contract-test",2,camera));
        BenchmarkContract.command(new JSONObject(score.toString()).put("seed",3429360255L),"aw-contract-test",2);checks++;
        deny(()->BenchmarkContract.command(new JSONObject(score.toString()).put("evaluator","model_says_done"),"aw-contract-test",2));
        deny(()->BenchmarkContract.command(new JSONObject(score.toString()).put("score",-1),"aw-contract-test",2));
        if(BenchmarkContract.modelDone("action")||BenchmarkContract.modelDone("step_limit")
                ||!BenchmarkContract.modelDone("finish")||!BenchmarkContract.modelDone("blocked"))throw new AssertionError("done_semantics");checks++;
        if(BenchmarkContract.agentScore(1.0,BenchmarkContract.modelDone("step_limit"))!=0.0)throw new AssertionError("budget_is_not_done");checks++;
        if(BenchmarkContract.agentScore(1.0,BenchmarkContract.modelDone("finish"))!=1.0)throw new AssertionError("finished_score");checks++;
        if(BenchmarkContract.agentScore(0.0,true)!=0.0)throw new AssertionError("finish_is_not_success");checks++;
        deny(()->BenchmarkContract.agentScore(Double.NaN,true));
        System.out.println(new JSONObject().put("passed",checks).put("model_calls",0).put("device_actions",0).put("benchmark_score_claimed",false));
    }
}
